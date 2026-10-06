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

  @Test fun resolutionViolationWithoutScannedCadenceFallsBackBeforeConversion() {
    val facts = compliantFacts(dimensions = Dimensions(2560, 1440)).copy(frameRate = MediaFact.Unknown)
    assertEquals(StandardInputAction.Fallback(FallbackReason.InsufficientEvidence),
      planner.plan(facts, options(), fullCapabilities().copy(sourceTimelineIsProven = false)).action)
  }

  @Test fun knownViolationPreservesH264AndHevcFamilies() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val conversion = conversion(compliantFacts(codec).copy(frameRate = known(121.0)))
      assertEquals(codec, conversion.sourceCodec)
      assertEquals(codec, conversion.outputCodec)
      assertEquals(setOf(PolicyRequirement.FrameRate), conversion.requirementsToRemediate)
      assertEquals(30.0, conversion.outputFrameRate, 0.0)
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
    assertEquals(Dimensions(1920, 1080), conversion.outputDimensions)
  }

  @Test fun wholeFileAudioViolationConvertsVideoInSameFamily() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val facts = compliantFacts(codec).copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec)))))
      val conversion = conversion(facts)
      assertTrue(conversion.requirementsToRemediate.isEmpty())
      assertEquals(setOf(PolicyRequirement.Audio), planner.plan(facts).evaluation.nonCompliantRequirements)
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
      sourceTimelineIsProven = true,
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
    val videoOnly = fullCapabilities().copy(canPrepareCompliantAacAudio = false)
    val facts = compliantFacts().copy(frameRate = known(121.0))
    for (audio in listOf(facts.audioTracks, MediaFact.Unknown, known(listOf(AudioTrack())),
      known(listOf(AudioTrack(known(AudioFormat.OtherCodec)))),
      known(listOf(AudioTrack(known(AudioFormat.OtherCodec)), AudioTrack())),
      known(listOf(AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.FivePointOne))))))) {
      val source = facts.copy(audioTracks = audio)
      assertTrue(fallback(source, videoOnly) is FallbackReason.UnsupportedConversion)
      assertTrue(planner.plan(source, capabilities = videoOnly.copy(canPrepareCompliantAacAudio = true)).action
        is StandardInputAction.Convert)
    }
    assertTrue(planner.plan(facts.copy(audioTracks = known(emptyList())), capabilities = videoOnly).action
      is StandardInputAction.Convert)
    // Lack of a local audio pipeline must never block compliant original bytes.
    assertEquals(StandardInputAction.UploadOriginal(OriginalReason.StandardInput), planner.plan(compliantFacts()).action)
  }

  @Test fun toneMappingWithUnknownCodecReportsInsufficientEvidence() {
    assertEquals(FallbackReason.InsufficientEvidence,
      fallback(hdrFacts().copy(videoCodec = MediaFact.Unknown), options = toneMapOptions()))
  }

  @Test fun betterAudioInspectionDoesNotAddAnotherCapabilityGate() {
    val capabilities = fullCapabilities().copy(remediableRequirements = setOf(PolicyRequirement.FrameRate))
    for (format in listOf(MediaFact.Unknown, known(AudioFormat.OtherCodec))) {
      val facts = compliantFacts().copy(frameRate = known(121.0), audioTracks = known(listOf(AudioTrack(format))))
      val plan = planner.plan(facts, capabilities = capabilities)
      assertTrue("AAC proof must cover known and unknown formats: ${plan.action}", plan.action is StandardInputAction.Convert)
      assertEquals(setOf(PolicyRequirement.FrameRate),
        (plan.action as StandardInputAction.Convert).conversion.requirementsToRemediate)
      assertEquals(if (format == MediaFact.Unknown) PolicyStatus.Unknown else PolicyStatus.NonCompliant,
        plan.evaluation.checks[PolicyRequirement.Audio])
    }
  }

  @Test fun conversionRequiresKnownUsableDimensionsAndFrameRate() {
    val facts = compliantFacts().copy(gopStructure = known(GopStructure.Open))
    val incompleteSources = listOf(
      facts.copy(displayDimensions = MediaFact.Unknown),
      facts.copy(displayDimensions = known(Dimensions(0, 1080))),
      facts.copy(frameRate = MediaFact.Unknown),
      facts.copy(frameRate = known(Double.NaN)),
      facts.copy(frameRate = known(0.0)),
    )
    for (resolution in MaximumResolution.entries) for (source in incompleteSources) {
      val plan = planner.plan(source, options(resolution), fullCapabilities())
      assertEquals(StandardInputAction.Fallback(FallbackReason.InsufficientEvidence), plan.action)
      assertTrue(PolicyRequirement.GopStructure in plan.evaluation.nonCompliantRequirements)
    }
    assertEquals(FallbackReason.InsufficientEvidence,
      fallback(hdrFacts().copy(displayDimensions = MediaFact.Unknown), options = toneMapOptions()))
  }

  @Test fun missingHdrEvidenceHasTheSameReasonForBothHandlingOptions() {
    for (handling in HdrHandling.entries) {
      assertEquals(FallbackReason.InsufficientEvidence,
        fallback(hdrFacts().copy(videoCodec = MediaFact.Unknown),
          options = InputStandardization(true, MaximumResolution.Default, handling)))
    }
    assertEquals(FallbackReason.InsufficientEvidence,
      fallback(hdrFacts().copy(pixelFormat = MediaFact.Unknown)))
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

  @Test fun unrelatedKnownViolationWithUnknownOutputSizeFallsBack() {
    val facts = compliantFacts().copy(displayDimensions = MediaFact.Unknown, frameRate = known(90.0),
      gopStructure = known(GopStructure.Open))
    val plan = planner.plan(facts, options(MaximumResolution.Preset3840x2160), fullCapabilities())
    assertEquals(setOf(PolicyRequirement.GopStructure), plan.evaluation.nonCompliantRequirements)
    assertEquals(StandardInputAction.Fallback(FallbackReason.InsufficientEvidence), plan.action)
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
      assertFalse(PolicyRequirement.DynamicRange in conversion.requirementsToRemediate)
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
      val conversion = conversion(facts, options(resolution))
      assertEquals(30.0, conversion.outputFrameRate, 0.0)
      assertEquals(OutputCadence.ConstantFrameRate, conversion.outputCadence)
    }
  }

  @Test fun unrelatedConversionPreservesKnownOrUnknownSourceCadence() {
    for (cadence in listOf(known(Cadence.Constant), known(Cadence.Variable), MediaFact.Unknown)) {
      val facts = compliantFacts().copy(cadence = cadence, gopStructure = known(GopStructure.Open))
      val plan = planner.plan(facts, capabilities = fullCapabilities().copy(
        remediableRequirements = setOf(PolicyRequirement.GopStructure)))
      assertTrue("GOP conversion must preserve the source timestamps: ${plan.action}", plan.action is StandardInputAction.Convert)
      val conversion = (plan.action as StandardInputAction.Convert).conversion
      assertEquals(OutputCadence.PreserveSourceTimestamps, conversion.outputCadence)
      assertEquals(30.0, conversion.outputFrameRate, 0.0)
      assertEquals(setOf(PolicyRequirement.GopStructure), conversion.requirementsToRemediate)
    }
  }

  @Test fun resamplingVariableOrUnknownCadenceRequiresFrameRateProof() {
    for (cadence in listOf(known(Cadence.Variable), MediaFact.Unknown)) {
      val facts = compliantFacts().copy(cadence = cadence, frameRate = known(121.0),
        gopStructure = known(GopStructure.Open))
      val gopOnly = fullCapabilities().copy(remediableRequirements = setOf(PolicyRequirement.GopStructure))
      val reason = fallback(facts, gopOnly) as FallbackReason.UnsupportedConversion
      assertEquals(setOf(ConversionCapabilityFailure.Remediation(PolicyRequirement.FrameRate)), reason.missingCapabilities)
      assertEquals(OutputCadence.ConstantFrameRate, reason.conversion.outputCadence)
      assertEquals(30.0, reason.conversion.outputFrameRate, 0.0)
      assertTrue(planner.plan(facts, capabilities = gopOnly.copy(
        remediableRequirements = setOf(PolicyRequirement.GopStructure, PolicyRequirement.FrameRate)))
        .action is StandardInputAction.Convert)
    }
  }

  @Test fun unsupportedAacChannelLayoutNeedsCompliantPreparationProof() {
    // Other includes layouts such as 7.1. Generic AAC encoding or Audio remediation cannot prove a downmix.
    val facts = compliantFacts().copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Other))))))
    val plan = planner.plan(facts, capabilities = fullCapabilities().copy(canPrepareCompliantAacAudio = false))
    assertEquals(setOf(PolicyRequirement.Audio), plan.evaluation.nonCompliantRequirements)
    val reason = (plan.action as StandardInputAction.Fallback).reason as FallbackReason.UnsupportedConversion
    assertEquals(setOf(ConversionCapabilityFailure.AacAudioPreparation), reason.missingCapabilities)
    assertTrue(reason.conversion.requirementsToRemediate.isEmpty())
    assertEquals(OutputAudio.AacFromFirstTrack, reason.conversion.outputAudio)
    // Accepted 5.1 remains eligible for unchanged upload without any preparation capability.
    assertEquals(StandardInputAction.UploadOriginal(OriginalReason.StandardInput), planner.plan(facts.copy(
      audioTracks = known(listOf(AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.FivePointOne))))))).action)
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

  @Test fun unsupportedConversionNamesEachMissingCapability() {
    val facts = hdrFacts().copy(gopStructure = known(GopStructure.Open))
    val full = fullCapabilities()
    val cases = listOf(
      full.copy(sourceIsDecodable = false) to ConversionCapabilityFailure.SourceDecode,
      full.copy(sourceTimelineIsProven = false) to ConversionCapabilityFailure.SourceTimeline,
      full.copy(encodableVideoCodecs = emptySet()) to ConversionCapabilityFailure.VideoEncode,
      full.copy(remediableRequirements = emptySet()) to
        ConversionCapabilityFailure.Remediation(PolicyRequirement.GopStructure),
      full.copy(canPrepareCompliantAacAudio = false) to ConversionCapabilityFailure.AacAudioPreparation,
      full.copy(toneMappableDynamicRanges = emptySet()) to ConversionCapabilityFailure.ToneMapping,
    )
    for ((capabilities, missing) in cases) {
      val reason = fallback(facts, capabilities, toneMapOptions()) as FallbackReason.UnsupportedConversion
      assertEquals(setOf(missing), reason.missingCapabilities)
      assertEquals(VideoCodec.Hevc, reason.conversion.outputCodec)
      assertEquals(DynamicRange.Hlg, reason.conversion.sourceDynamicRange)
    }
    assertTrue(planner.plan(compliantFacts(), capabilities = full.copy(sourceTimelineIsProven = false))
      .action is StandardInputAction.UploadOriginal)
    val allMissing = fallback(facts, PlanningCapabilities(), toneMapOptions()) as FallbackReason.UnsupportedConversion
    assertEquals(cases.map { it.second }.toSet(), allMissing.missingCapabilities)
  }

  @Test fun audioOnlyConversionUsesTheDedicatedPreparationProof() {
    val facts = compliantFacts().copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec)))))
    val capabilities = fullCapabilities().copy(remediableRequirements = emptySet())
    assertTrue(planner.plan(facts, capabilities = capabilities).action is StandardInputAction.Convert)
    val reason = fallback(facts, capabilities.copy(canPrepareCompliantAacAudio = false)) as FallbackReason.UnsupportedConversion
    assertEquals(setOf(ConversionCapabilityFailure.AacAudioPreparation), reason.missingCapabilities)
    assertTrue(reason.conversion.requirementsToRemediate.isEmpty())
  }

  @Test fun unremediableViolationsFallBack() {
    val cases = mapOf(
      PolicyRequirement.FrameRate to compliantFacts().copy(frameRate = known(121.0)),
      PolicyRequirement.AverageBitrate to compliantFacts().copy(averageBitrate = known(8_000_001L)),
      PolicyRequirement.MaximumGopBitrate to compliantFacts().copy(maximumGopBitrate = known(16_000_001L)),
      PolicyRequirement.KeyframeInterval to compliantFacts().copy(maximumKeyframeIntervalSeconds = known(21.0)),
      PolicyRequirement.GopStructure to compliantFacts().copy(gopStructure = known(GopStructure.Open)),
      PolicyRequirement.PixelFormat to compliantFacts().copy(pixelFormat = known(PixelFormat(10, ChromaSubsampling.Yuv420))),
      PolicyRequirement.EditList to compliantFacts().copy(editList = known(EditList.Complex)),
      PolicyRequirement.VideoResolution to compliantFacts(dimensions = Dimensions(2048, 1152)),
      PolicyRequirement.VideoCodec to compliantFacts(VideoCodec.Other),
    )
    for ((requirement, facts) in cases) {
      val reason = fallback(facts, fullCapabilities().copy(remediableRequirements = PolicyRequirement.entries.toSet() - requirement))
      assertTrue("$requirement", reason is FallbackReason.UnsupportedConversion)
      assertEquals(setOf(ConversionCapabilityFailure.Remediation(requirement)),
        (reason as FallbackReason.UnsupportedConversion).missingCapabilities)
    }
  }

  @Test fun missingCodecOrDynamicRangeWithKnownViolationFallsBack() {
    for (facts in listOf(
      compliantFacts().copy(videoCodec = MediaFact.Unknown, frameRate = known(121.0)),
      compliantFacts().copy(dynamicRange = MediaFact.Unknown, frameRate = known(121.0)),
      compliantFacts(dimensions = Dimensions(2048, 1152)).copy(dynamicRange = MediaFact.Unknown))) {
      assertEquals(FallbackReason.InsufficientEvidence, fallback(facts))
    }
  }

  @Test fun unknownMeasurementsDoNotEraseKnownViolationsOrFabricateTargets() {
    val facts = compliantFacts().copy(averageBitrate = known(8_000_001L), maximumGopBitrate = MediaFact.Unknown,
      frameRate = MediaFact.Unknown, displayDimensions = MediaFact.Unknown)
    val plan = planner.plan(facts, capabilities = fullCapabilities())
    assertEquals(setOf(PolicyRequirement.AverageBitrate), plan.evaluation.nonCompliantRequirements)
    assertEquals(StandardInputAction.Fallback(FallbackReason.InsufficientEvidence), plan.action)
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
    for (facts in listOf(hdrFacts().copy(pixelFormat = MediaFact.Unknown), hdrFacts().copy(videoCodec = MediaFact.Unknown))) {
      assertEquals(FallbackReason.InsufficientEvidence, fallback(facts))
    }
    for (facts in listOf(hdrFacts().copy(pixelFormat = known(PixelFormat(8, ChromaSubsampling.Yuv420))),
      hdrFacts().copy(videoCodec = known(VideoCodec.H264)),
      hdrFacts().copy(videoCodec = known(VideoCodec.H264), pixelFormat = MediaFact.Unknown))) {
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
      assertEquals(expected, conversion(compliantFacts(dimensions = source)).outputDimensions)
    }
  }

  @Test fun everyTierBoundsOutputWithoutUpscaling() {
    val sizes = listOf(Dimensions(640, 360), Dimensions(1919, 1079), Dimensions(3840, 2160),
      Dimensions(2160, 3840), Dimensions(4096, 4096), Dimensions(2, 2), Dimensions(100, 3999))
    for (resolution in MaximumResolution.entries) for (source in sizes) {
      val conversion = conversion(compliantFacts(dimensions = source).copy(gopStructure = known(GopStructure.Open)), options(resolution))
      val output = conversion.outputDimensions
      assertEquals(source, conversion.sourceDisplayDimensions)
      assertEquals(30.0, conversion.outputFrameRate, 0.0)
      assertTrue("$source $resolution -> $output", output.fitsWithin(Dimensions(resolution.width, resolution.height)))
      assertTrue(output.width <= source.width && output.height <= source.height)
      assertEquals(0, output.width % 2)
      assertEquals(0, output.height % 2)
    }
  }

  @Test fun nearestEvenAlignmentMatchesSwiftCinematicCase() {
    assertEquals(Dimensions(1280, 546), conversion(compliantFacts(dimensions = Dimensions(1920, 818)),
      options(MaximumResolution.Preset1280x720)).outputDimensions)
  }

  @Test fun onePixelAxisCannotBeConvertedWithoutUpscaling() {
    assertEquals(FallbackReason.InsufficientEvidence,
      fallback(compliantFacts(dimensions = Dimensions(1, 100)).copy(gopStructure = known(GopStructure.Open))))
  }

  @Test fun downscaledOutputUsesOutputTierForFrameRate() {
    val source = compliantFacts(dimensions = Dimensions(3840, 2160)).copy(frameRate = known(120.0))
    assertEquals(30.0, conversion(source, options(MaximumResolution.Preset3840x2160)).outputFrameRate, 0.0)
    assertEquals(120.0, conversion(source, options(MaximumResolution.Preset1920x1080)).outputFrameRate, 0.0)
  }

  @Test fun downscaledOutputDoesNotRequireUnneededFrameRateOrKeyframeRemediation() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val source = compliantFacts(codec, Dimensions(4096, 4096)).copy(frameRate = known(90.0),
        maximumKeyframeIntervalSeconds = known(if (codec == VideoCodec.H264) 15.0 else 8.0))
      val plan = planner.plan(source, options(MaximumResolution.Preset2560x1440),
        fullCapabilities().copy(remediableRequirements = setOf(PolicyRequirement.VideoResolution)))
      assertEquals(setOf(PolicyRequirement.FrameRate, PolicyRequirement.KeyframeInterval),
        plan.evaluation.nonCompliantRequirements)
      assertTrue("Only resizing is needed for the output tier: ${plan.action}", plan.action is StandardInputAction.Convert)
      val conversion = (plan.action as StandardInputAction.Convert).conversion
      assertEquals(Dimensions(1440, 1440), conversion.outputDimensions)
      assertEquals(90.0, conversion.outputFrameRate, 0.0)
      assertEquals(OutputCadence.PreserveSourceTimestamps, conversion.outputCadence)
      assertEquals(setOf(PolicyRequirement.VideoResolution), conversion.requirementsToRemediate)
      assertEquals(8_000_000L, conversion.outputPolicyLimits.maximumAverageBitrate)
      assertEquals(16_000_000L, conversion.outputPolicyLimits.maximumGopBitrate)
      assertEquals(if (codec == VideoCodec.H264) 20.0 else 10.0,
        conversion.outputPolicyLimits.maximumKeyframeIntervals.getValue(codec), 0.0)
    }
  }

  @Test fun downscaledOutputRequiresStricterBitrateRemediation() {
    val source = compliantFacts(dimensions = Dimensions(4096, 4096)).copy(
      averageBitrate = known(15_000_000L), maximumGopBitrate = known(17_000_000L))
    val plan = planner.plan(source, options(MaximumResolution.Preset2560x1440),
      fullCapabilities().copy(remediableRequirements = setOf(PolicyRequirement.VideoResolution)))
    assertEquals(PolicyStatus.Compliant, plan.evaluation.outcome)
    assertTrue("Generated output needs the lower-tier bitrate proof: ${plan.action}", plan.action is StandardInputAction.Fallback)
    val reason = (plan.action as StandardInputAction.Fallback).reason as FallbackReason.UnsupportedConversion
    assertEquals(setOf(ConversionCapabilityFailure.Remediation(PolicyRequirement.AverageBitrate),
      ConversionCapabilityFailure.Remediation(PolicyRequirement.MaximumGopBitrate)), reason.missingCapabilities)
    assertEquals(setOf(PolicyRequirement.VideoResolution, PolicyRequirement.AverageBitrate,
      PolicyRequirement.MaximumGopBitrate), reason.conversion.requirementsToRemediate)
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
