package com.mux.video.upload.internal.standardization

import com.mux.video.upload.api.HdrHandling
import com.mux.video.upload.internal.InputStandardization
import com.mux.video.upload.internal.MaximumResolution
import org.junit.Assert.*
import org.junit.Test

class StandardInputPlannerTests {
  private val planner = StandardInputPlanner()

  @Test fun compliantSharedCasesUploadOriginalWithoutEncodeCapabilities() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      for (resolution in MaximumResolution.entries) {
        for (depth in if (codec == VideoCodec.Hevc) listOf(8, 10) else listOf(8)) {
          val facts = compliantFacts(codec, Dimensions(resolution.width, resolution.height),
            pixelFormat = PixelFormat(depth, ChromaSubsampling.Yuv420))
          assertEquals("$codec $resolution $depth", StandardInputAction.UploadOriginal(OriginalReason.StandardInput),
            planner.plan(facts, options(resolution)).action)
        }
      }
    }
  }

  @Test fun disabledStandardizationAlwaysUploadsOriginal() {
    for (range in DynamicRange.entries) {
      val facts = compliantFacts(VideoCodec.Other, dynamicRange = range).copy(videoTrackCount = known(2),
        frameRate = known(121.0), editList = known(EditList.Complex))
      assertEquals(StandardInputAction.UploadOriginal(OriginalReason.StandardizationNotRequested),
        planner.plan(facts, InputStandardization(false, MaximumResolution.Default, HdrHandling.ToneMapToSDR)).action)
    }
  }

  @Test fun unknownOnlyFactsUploadOriginalWithoutConversion() {
    for (facts in listOf(MediaFacts(), compliantFacts().copy(maximumGopBitrate = MediaFact.Unknown),
      compliantFacts().copy(dynamicRange = MediaFact.Unknown), compliantFacts().copy(audioTracks = MediaFact.Unknown))) {
      assertEquals(StandardInputAction.UploadOriginal(OriginalReason.NoKnownStandardInputViolation), planner.plan(facts).action)
      assertEquals(PolicyStatus.Unknown, planner.plan(facts).evaluation.outcome)
    }
  }

  @Test fun knownViolationPreservesH264AndHevcFamilies() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val conversion = conversion(compliantFacts(codec).copy(frameRate = known(121.0)))
      assertEquals(codec, conversion.sourceCodec)
      assertEquals(codec, conversion.outputCodec)
      assertEquals(setOf(PolicyRequirement.FrameRate), conversion.requirementsToRemediate)
      assertEquals(known(30.0), conversion.outputFrameRate)
      assertFalse(conversion.toneMapsToSdr)
      assertEquals(DynamicRange.Sdr, conversion.outputDynamicRange)
    }
  }

  @Test fun selectedOutputSizeTriggersConversionBelowPublishedSourceLimit() {
    val facts = compliantFacts(dimensions = Dimensions(2048, 1152))
    val plan = planner.plan(facts, capabilities = fullCapabilities())
    assertEquals(PolicyStatus.Compliant, plan.evaluation.outcome)
    val conversion = (plan.action as StandardInputAction.Convert).conversion
    assertEquals(setOf(PolicyRequirement.VideoResolution), conversion.requirementsToRemediate)
    assertEquals(known(Dimensions(1920, 1080)), conversion.outputDimensions)
  }

  @Test fun wholeFileAudioViolationConvertsVideoInSameFamily() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val conversion = conversion(compliantFacts(codec).copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec))))))
      assertEquals(setOf(PolicyRequirement.Audio), conversion.requirementsToRemediate)
      assertEquals(codec, conversion.outputCodec)
      assertEquals(OutputAudio.AacFromFirstTrack, conversion.outputAudio)
    }
  }

  @Test fun multipleAudioTracksAloneDoNotForceConversion() {
    val tracks = listOf(AudioTrack(known(AudioFormat.OtherCodec)), AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Stereo))))
    val facts = compliantFacts().copy(audioTracks = known(tracks))
    assertEquals(StandardInputAction.UploadOriginal(OriginalReason.NoKnownStandardInputViolation), planner.plan(facts).action)
    assertEquals(setOf(PolicyRequirement.Audio), planner.plan(facts).evaluation.unknownRequirements)
  }

  @Test fun anotherViolationWithMultipleAudioTracksSelectsFirstTrack() {
    val facts = compliantFacts().copy(frameRate = known(121.0),
      audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec)), AudioTrack())))
    val conversion = conversion(facts)
    assertEquals(OutputAudio.AacFromFirstTrack, conversion.outputAudio)
    assertEquals(setOf(PolicyRequirement.FrameRate), conversion.requirementsToRemediate)
    assertEquals(StandardInputAction.UploadOriginal(OriginalReason.NoKnownStandardInputViolation),
      planner.plan(facts.copy(frameRate = known(30.0))).action)
  }

  @Test fun conversionDoesNotGuessUnknownAudioPresenceMeansSilence() {
    val facts = compliantFacts().copy(frameRate = known(121.0))
    assertEquals(OutputAudio.AacFromFirstTrackIfPresent, conversion(facts.copy(audioTracks = MediaFact.Unknown)).outputAudio)
    assertEquals(OutputAudio.None, conversion(facts.copy(audioTracks = known(emptyList()))).outputAudio)
    assertEquals(OutputAudio.AacFromFirstTrack, conversion(facts.copy(audioTracks = known(listOf(AudioTrack())))).outputAudio)
  }

  @Test fun unknownAudioRequiresIndependentAacPreparationProof() {
    val videoCapabilities = PlanningCapabilities(
      sourceIsDecodable = true,
      encodableVideoCodecs = setOf(VideoCodec.H264),
      remediableRequirements = setOf(PolicyRequirement.FrameRate),
    )
    val facts = compliantFacts().copy(frameRate = known(121.0))
    for (audio in listOf(MediaFact.Unknown, known(listOf(AudioTrack())),
      known(listOf(AudioTrack(known(AudioFormat.OtherCodec)), AudioTrack())))) {
      val action = planner.plan(facts.copy(audioTracks = audio), capabilities = videoCapabilities).action
      assertTrue("Audio must have its own proven AAC path: $action", action is StandardInputAction.Fallback)
      assertTrue((action as StandardInputAction.Fallback).reason is FallbackReason.UnsupportedConversion)
    }
    assertTrue(planner.plan(facts.copy(audioTracks = known(emptyList())), capabilities = videoCapabilities)
      .action is StandardInputAction.Convert)
  }

  @Test fun aacPreparationProofCoversCopyAndEncodingForTheSelectedTrack() {
    val videoOnly = fullCapabilities().copy(canProduceAacAudio = false)
    val facts = compliantFacts().copy(frameRate = known(121.0))
    for (audio in listOf(facts.audioTracks, MediaFact.Unknown, known(listOf(AudioTrack())),
      known(listOf(AudioTrack(known(AudioFormat.OtherCodec)))),
      known(listOf(AudioTrack(known(AudioFormat.OtherCodec)), AudioTrack())),
      known(listOf(AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.FivePointOne))))))) {
      val source = facts.copy(audioTracks = audio)
      assertTrue(fallback(source, videoOnly) is FallbackReason.UnsupportedConversion)
      assertTrue(planner.plan(source, capabilities = videoOnly.copy(canProduceAacAudio = true)).action
        is StandardInputAction.Convert)
    }
    assertTrue(planner.plan(facts.copy(audioTracks = known(emptyList())), capabilities = videoOnly).action
      is StandardInputAction.Convert)
    // Lack of a local audio pipeline must never block compliant original bytes.
    assertEquals(StandardInputAction.UploadOriginal(OriginalReason.StandardInput), planner.plan(compliantFacts()).action)
  }

  @Test fun toneMappingWithUnknownCodecReportsInsufficientEvidence() {
    assertEquals(FallbackReason.InsufficientEvidenceForConversion,
      fallback(hdrFacts().copy(videoCodec = MediaFact.Unknown), options = toneMapOptions()))
  }

  @Test fun unknownSizeDoesNotChooseTheLooserOrStricterTier() {
    val facts = compliantFacts().copy(displayDimensions = MediaFact.Unknown, frameRate = known(90.0),
      averageBitrate = known(15_000_000L), maximumGopBitrate = MediaFact.Unknown)
    for (resolution in listOf(MaximumResolution.Preset2560x1440, MaximumResolution.Preset3840x2160)) {
      val plan = planner.plan(facts, options(resolution), fullCapabilities())
      assertEquals(StandardInputAction.UploadOriginal(OriginalReason.NoKnownStandardInputViolation), plan.action)
      assertEquals(setOf(PolicyRequirement.VideoResolution, PolicyRequirement.FrameRate,
        PolicyRequirement.AverageBitrate, PolicyRequirement.MaximumGopBitrate), plan.evaluation.unknownRequirements)
    }
  }

  @Test fun unrelatedKnownViolationWithUnknownOutputSizeDoesNotInventFrameRate() {
    val facts = compliantFacts().copy(displayDimensions = MediaFact.Unknown, frameRate = known(90.0),
      gopStructure = known(GopStructure.Open))
    val conversion = conversion(facts, options(MaximumResolution.Preset3840x2160))
    assertEquals(setOf(PolicyRequirement.GopStructure), conversion.requirementsToRemediate)
    assertEquals(MediaFact.Unknown, conversion.outputDimensions)
    assertEquals(MediaFact.Unknown, conversion.outputFrameRate)
  }

  @Test fun provenToneMappingRemediatesDynamicRangeForEitherCodec() {
    val capabilities = fullCapabilities().copy(remediableRequirements = emptySet())
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) for (range in listOf(DynamicRange.Hlg, DynamicRange.Pq)) {
      val facts = hdrFacts(range).copy(videoCodec = known(codec),
        pixelFormat = known(PixelFormat(if (codec == VideoCodec.H264) 8 else 10, ChromaSubsampling.Yuv420)))
      val action = planner.plan(facts, toneMapOptions(), capabilities).action
      assertTrue("Proven tone mapping must be enough for $codec $range: $action", action is StandardInputAction.Convert)
      val conversion = (action as StandardInputAction.Convert).conversion
      assertEquals(codec, conversion.outputCodec)
      assertTrue(conversion.toneMapsToSdr)
    }
    // Tone mapping does not establish unrelated pixel-format remediation.
    assertTrue(fallback(hdrFacts().copy(videoCodec = known(VideoCodec.H264)), capabilities, toneMapOptions())
      is FallbackReason.UnsupportedConversion)
    assertTrue(fallback(hdrFacts().copy(videoCodec = known(VideoCodec.H264),
      pixelFormat = known(PixelFormat(8, ChromaSubsampling.Yuv420))),
      capabilities.copy(toneMappableDynamicRanges = emptySet()), toneMapOptions()) is FallbackReason.UnsupportedConversion)
  }

  @Test fun thirtyFpsTargetsRequireProvenCadenceRemediation() {
    for ((facts, resolution) in listOf(
      compliantFacts().copy(frameRate = known(2.0)) to MaximumResolution.Default,
      compliantFacts(dimensions = Dimensions(3840, 2160)).copy(frameRate = known(120.0)) to MaximumResolution.Preset3840x2160)) {
      assertTrue(fallback(facts, fullCapabilities().copy(remediableRequirements = PolicyRequirement.entries.toSet() -
        PolicyRequirement.FrameRate), options(resolution)) is FallbackReason.UnsupportedConversion)
      assertEquals(known(30.0), conversion(facts, options(resolution)).outputFrameRate)
    }
  }

  @Test fun otherDecodableSdrCodecConvertsToH264() {
    val conversion = conversion(compliantFacts(VideoCodec.Other).copy(pixelFormat = known(PixelFormat(10, ChromaSubsampling.Other))))
    assertEquals(VideoCodec.Other, conversion.sourceCodec)
    assertEquals(VideoCodec.H264, conversion.outputCodec)
    assertTrue(PolicyRequirement.VideoCodec in conversion.requirementsToRemediate)
    assertEquals(PixelFormat(8, ChromaSubsampling.Yuv420), conversion.outputPixelFormat)
  }

  @Test fun noHevcEncoderFallsBackWithoutSubstitutingH264() {
    val facts = compliantFacts(VideoCodec.Hevc).copy(maximumKeyframeIntervalSeconds = known(11.0))
    val reason = fallback(facts, fullCapabilities().copy(encodableVideoCodecs = setOf(VideoCodec.H264)))
      as FallbackReason.UnsupportedConversion
    assertEquals(VideoCodec.Hevc, reason.conversion.outputCodec)
    assertEquals(setOf(PolicyRequirement.KeyframeInterval), reason.conversion.requirementsToRemediate)
  }

  @Test fun undecodableSourceFallsBack() {
    val reason = fallback(compliantFacts(VideoCodec.Other), fullCapabilities().copy(sourceIsDecodable = false))
    assertTrue(reason is FallbackReason.UnsupportedConversion)
  }

  @Test fun unremediableViolationsFallBack() {
    val cases = mapOf(
      PolicyRequirement.FrameRate to compliantFacts().copy(frameRate = known(121.0)),
      PolicyRequirement.AverageBitrate to compliantFacts().copy(averageBitrate = known(8_000_001L)),
      PolicyRequirement.MaximumGopBitrate to compliantFacts().copy(maximumGopBitrate = known(16_000_001L)),
      PolicyRequirement.KeyframeInterval to compliantFacts().copy(maximumKeyframeIntervalSeconds = known(21.0)),
      PolicyRequirement.GopStructure to compliantFacts().copy(gopStructure = known(GopStructure.Open)),
      PolicyRequirement.PixelFormat to compliantFacts().copy(pixelFormat = known(PixelFormat(10, ChromaSubsampling.Yuv420))),
      PolicyRequirement.Audio to compliantFacts().copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec))))),
      PolicyRequirement.EditList to compliantFacts().copy(editList = known(EditList.Complex)),
      PolicyRequirement.VideoResolution to compliantFacts(dimensions = Dimensions(2048, 1152)),
      PolicyRequirement.VideoCodec to compliantFacts(VideoCodec.Other),
    )
    for ((requirement, facts) in cases) {
      val reason = fallback(facts, fullCapabilities().copy(remediableRequirements = PolicyRequirement.entries.toSet() - requirement))
      assertTrue("$requirement", reason is FallbackReason.UnsupportedConversion)
    }
  }

  @Test fun missingCodecOrDynamicRangeWithKnownViolationFallsBack() {
    for (facts in listOf(
      compliantFacts().copy(videoCodec = MediaFact.Unknown, frameRate = known(121.0)),
      compliantFacts().copy(dynamicRange = MediaFact.Unknown, frameRate = known(121.0)),
      compliantFacts(dimensions = Dimensions(2048, 1152)).copy(dynamicRange = MediaFact.Unknown))) {
      assertEquals(FallbackReason.InsufficientEvidenceForConversion, fallback(facts))
    }
  }

  @Test fun unknownMeasurementsDoNotEraseKnownViolationsOrFabricateTargets() {
    val facts = compliantFacts().copy(averageBitrate = known(8_000_001L), maximumGopBitrate = MediaFact.Unknown,
      frameRate = MediaFact.Unknown, displayDimensions = MediaFact.Unknown)
    val conversion = conversion(facts)
    assertEquals(setOf(PolicyRequirement.AverageBitrate), conversion.requirementsToRemediate)
    assertEquals(MediaFact.Unknown, conversion.outputDimensions)
    assertEquals(MediaFact.Unknown, conversion.outputFrameRate)
  }

  @Test fun eligibleHlgAndPqAreIntentionalOriginalPlansWithoutLocalEncoder() {
    for (range in listOf(DynamicRange.Hlg, DynamicRange.Pq)) {
      assertEquals(StandardInputAction.UploadOriginal(OriginalReason.PreserveHdr(range)), planner.plan(hdrFacts(range)).action)
    }
  }

  @Test fun eligibleHdrWithUnrelatedUnknownFactsStillUploadsOriginal() {
    val facts = hdrFacts().copy(maximumGopBitrate = MediaFact.Unknown,
      audioTracks = known(listOf(AudioTrack(), AudioTrack())))
    val plan = planner.plan(facts)
    assertEquals(StandardInputAction.UploadOriginal(OriginalReason.PreserveHdr(DynamicRange.Hlg)), plan.action)
    assertEquals(setOf(PolicyRequirement.MaximumGopBitrate, PolicyRequirement.Audio), plan.evaluation.unknownRequirements)
  }

  @Test fun preservedHdrWithKnownViolationFallsBackWithoutHdrConversion() {
    for (range in listOf(DynamicRange.Hlg, DynamicRange.Pq)) {
      assertEquals(FallbackReason.NonStandardHdr(range, setOf(PolicyRequirement.AverageBitrate)),
        fallback(hdrFacts(range).copy(averageBitrate = known(8_000_001L))))
      assertEquals(FallbackReason.NonStandardHdr(range, setOf(PolicyRequirement.VideoResolution)),
        fallback(hdrFacts(range).copy(displayDimensions = known(Dimensions(2048, 1152)))))
    }
  }

  @Test fun unknownHdrEligibilityFallsBack() {
    for (facts in listOf(hdrFacts().copy(pixelFormat = MediaFact.Unknown), hdrFacts().copy(videoCodec = MediaFact.Unknown),
      hdrFacts().copy(pixelFormat = known(PixelFormat(8, ChromaSubsampling.Yuv420))), hdrFacts().copy(videoCodec = known(VideoCodec.H264)))) {
      assertEquals(FallbackReason.UnsupportedHdr(DynamicRange.Hlg), fallback(facts))
    }
  }

  @Test fun hdrPreservationUnavailableFallsBack() {
    assertEquals(FallbackReason.HdrPreservationUnavailable(DynamicRange.Pq),
      fallback(hdrFacts(DynamicRange.Pq), fullCapabilities().copy(preservableHdrDynamicRanges = setOf(DynamicRange.Hlg))))
  }

  @Test fun toneMappingAlwaysConvertsAndKeepsCodecFamily() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) for (range in listOf(DynamicRange.Hlg, DynamicRange.Pq)) {
      val conversion = conversion(hdrFacts(range).copy(videoCodec = known(codec)), toneMapOptions())
      assertEquals(codec, conversion.outputCodec)
      assertTrue(conversion.toneMapsToSdr)
      assertEquals(DynamicRange.Sdr, conversion.outputDynamicRange)
      assertEquals(PixelFormat(8, ChromaSubsampling.Yuv420), conversion.outputPixelFormat)
    }
  }

  @Test fun unsupportedToneMappingFallsBackPerRange() {
    for (range in listOf(DynamicRange.Hlg, DynamicRange.Pq)) {
      val reason = fallback(hdrFacts(range), fullCapabilities().copy(toneMappableDynamicRanges = emptySet()), toneMapOptions())
        as FallbackReason.UnsupportedConversion
      assertEquals(range, reason.conversion.sourceDynamicRange)
      assertTrue(reason.conversion.toneMapsToSdr)
    }
  }

  @Test fun unsupportedHdrAndHdrInOtherCodecFallBackForEitherOption() {
    for (handling in HdrHandling.entries) {
      val options = InputStandardization(true, MaximumResolution.Default, handling)
      for (range in listOf(DynamicRange.OtherHdr, DynamicRange.DolbyVision)) {
        assertEquals(FallbackReason.UnsupportedHdr(range), fallback(hdrFacts(range), options = options))
      }
      assertEquals(FallbackReason.UnsupportedHdr(DynamicRange.Hlg),
        fallback(hdrFacts().copy(videoCodec = known(VideoCodec.Other)), options = options))
    }
  }

  @Test fun sdrDoesNotToneMapBecauseOptionIsSet() {
    val facts = compliantFacts().copy(frameRate = known(121.0))
    assertFalse(conversion(facts, toneMapOptions()).toneMapsToSdr)
    assertEquals(StandardInputAction.UploadOriginal(OriginalReason.StandardInput), planner.plan(compliantFacts(), toneMapOptions()).action)
  }

  @Test fun tenBitHevcSdrConversionKeepsTenBit420() {
    val facts = compliantFacts(VideoCodec.Hevc, pixelFormat = PixelFormat(10, ChromaSubsampling.Yuv420))
      .copy(frameRate = known(121.0))
    assertEquals(PixelFormat(10, ChromaSubsampling.Yuv420), conversion(facts).outputPixelFormat)
  }

  @Test fun unsafeVideoTrackStructuresFallBack() {
    for (count in listOf(-1, 0, 2, 3)) {
      assertEquals(FallbackReason.UnsafeVideoTrackStructure, fallback(compliantFacts().copy(videoTrackCount = known(count))))
    }
  }

  @Test fun portraitAndSquareConversionsFitBothAxes() {
    val cases = listOf(Dimensions(2160, 3840) to Dimensions(1080, 1920),
      Dimensions(1920, 1920) to Dimensions(1080, 1080), Dimensions(4096, 2160) to Dimensions(1920, 1012))
    for ((source, expected) in cases) {
      assertEquals(known(expected), conversion(compliantFacts(dimensions = source)).outputDimensions)
    }
  }

  @Test fun everyTierBoundsOutputWithoutUpscaling() {
    val sizes = listOf(Dimensions(640, 360), Dimensions(1919, 1079), Dimensions(3840, 2160),
      Dimensions(2160, 3840), Dimensions(4096, 4096), Dimensions(2, 2), Dimensions(100, 3999))
    for (resolution in MaximumResolution.entries) for (source in sizes) {
      val conversion = conversion(compliantFacts(dimensions = source).copy(gopStructure = known(GopStructure.Open)), options(resolution))
      val output = conversion.outputDimensions.valueOrNull!!
      assertTrue("$source $resolution -> $output", output.fitsWithin(Dimensions(resolution.width, resolution.height)))
      assertTrue(output.width <= source.width && output.height <= source.height)
      assertEquals(0, output.width % 2)
      assertEquals(0, output.height % 2)
    }
  }

  @Test fun nearestEvenAlignmentMatchesSwiftCinematicCase() {
    assertEquals(known(Dimensions(1280, 546)), conversion(compliantFacts(dimensions = Dimensions(1920, 818)),
      options(MaximumResolution.Preset1280x720)).outputDimensions)
  }

  @Test fun onePixelAxisCannotBeConvertedWithoutUpscaling() {
    assertEquals(FallbackReason.InsufficientEvidenceForConversion,
      fallback(compliantFacts(dimensions = Dimensions(1, 100)).copy(gopStructure = known(GopStructure.Open))))
  }

  @Test fun downscaledOutputUsesOutputTierForFrameRate() {
    val source = compliantFacts(dimensions = Dimensions(3840, 2160)).copy(frameRate = known(120.0))
    assertEquals(known(30.0), conversion(source, options(MaximumResolution.Preset3840x2160)).outputFrameRate)
    assertEquals(known(120.0), conversion(source, options(MaximumResolution.Preset1920x1080)).outputFrameRate)
  }

  @Test fun plansAreDeterministicAndDoNotMutateInput() {
    val facts = compliantFacts().copy(frameRate = known(121.0))
    val original = facts.copy()
    val capabilities = fullCapabilities()
    assertEquals(planner.plan(facts, capabilities = capabilities), planner.plan(facts, capabilities = capabilities))
    assertEquals(original, facts)
    assertEquals(PolicyRequirement.entries.toSet(), capabilities.remediableRequirements)
    assertEquals(planner.plan(facts, options(), capabilities),
      planner.plan(facts, options(MaximumResolution.Preset1920x1080), capabilities))
  }

  private fun conversion(facts: MediaFacts, options: InputStandardization = InputStandardization()): StandardInputConversion {
    val action = planner.plan(facts, options, fullCapabilities()).action
    assertTrue("Expected conversion, got $action", action is StandardInputAction.Convert)
    return (action as StandardInputAction.Convert).conversion
  }

  private fun fallback(
    facts: MediaFacts,
    capabilities: PlanningCapabilities = fullCapabilities(),
    options: InputStandardization = InputStandardization(),
  ): FallbackReason {
    val action = planner.plan(facts, options, capabilities).action
    assertTrue("Expected fallback, got $action", action is StandardInputAction.Fallback)
    return (action as StandardInputAction.Fallback).reason
  }

  private fun toneMapOptions() = InputStandardization(true, MaximumResolution.Default, HdrHandling.ToneMapToSDR)
}
