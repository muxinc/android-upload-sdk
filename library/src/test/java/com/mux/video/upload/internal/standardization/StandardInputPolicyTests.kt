package com.mux.video.upload.internal.standardization

import com.mux.video.upload.internal.MaximumResolution
import org.junit.Assert.*
import org.junit.Test

class StandardInputPolicyTests {
  private val evaluator = StandardInputPolicyEvaluator()

  @Test fun resolutionSelectionsKeepAcceptanceSeparateFromOutput() {
    for (resolution in MaximumResolution.entries) {
      val selection = selection(resolution)
      assertEquals(Dimensions(resolution.width, resolution.height), selection.generatedOutputDimensions)
      assertEquals(if (resolution.width > 2048) AcceptanceTier.HighResolution else AcceptanceTier.UpTo1080p,
        selection.acceptanceTier)
    }
    assertEquals(selection(), selection(MaximumResolution.Preset1920x1080))
  }

  @Test fun lowerTierInclusiveBoundariesMatchSwift() {
    val facts = compliantFacts().copy(frameRate = known(120.0), averageBitrate = known(8_000_000L),
      maximumGopBitrate = known(16_000_000L), maximumKeyframeIntervalSeconds = known(20.0))
    assertEquals(PolicyStatus.Compliant, evaluate(facts).outcome)
    assertEquals(PolicyStatus.Compliant, evaluate(facts.copy(frameRate = known(5.0))).outcome)
  }

  @Test fun frameRateBoundariesForBothTiers() {
    for (resolution in MaximumResolution.entries) {
      val high = resolution.width > 2048
      val facts = compliantFacts(dimensions = Dimensions(resolution.width, resolution.height))
      val maximum = if (high) 60.0 else 120.0
      for ((rate, expected) in listOf(4.999 to PolicyStatus.NonCompliant, 5.0 to PolicyStatus.Compliant,
        maximum to PolicyStatus.Compliant, maximum + 0.001 to PolicyStatus.NonCompliant)) {
        assertEquals("$resolution $rate", expected, evaluate(facts.copy(frameRate = known(rate)), resolution)
          .checks[PolicyRequirement.FrameRate])
      }
    }
  }

  @Test fun codecKeyframeLimitsForBothTiers() {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      for ((resolution, maximum) in listOf(
        MaximumResolution.Default to if (codec == VideoCodec.H264) 20.0 else 10.0,
        MaximumResolution.Preset2560x1440 to if (codec == VideoCodec.H264) 10.0 else 6.0,
        MaximumResolution.Preset3840x2160 to if (codec == VideoCodec.H264) 10.0 else 6.0)) {
        val facts = compliantFacts(codec, Dimensions(resolution.width, resolution.height))
        assertEquals(PolicyStatus.Compliant, evaluate(facts.copy(maximumKeyframeIntervalSeconds = known(maximum)), resolution)
          .checks[PolicyRequirement.KeyframeInterval])
        assertEquals(PolicyStatus.NonCompliant, evaluate(facts.copy(maximumKeyframeIntervalSeconds = known(maximum + 0.001)), resolution)
          .checks[PolicyRequirement.KeyframeInterval])
      }
    }
  }

  @Test fun averageAndGopBitratesAreIndependent() {
    val averageViolation = evaluate(compliantFacts().copy(averageBitrate = known(8_000_001L)))
    assertEquals(setOf(PolicyRequirement.AverageBitrate), averageViolation.nonCompliantRequirements)
    val gopViolation = evaluate(compliantFacts().copy(maximumGopBitrate = known(16_000_001L)))
    assertEquals(setOf(PolicyRequirement.MaximumGopBitrate), gopViolation.nonCompliantRequirements)
    assertEquals(PolicyStatus.Compliant, evaluate(compliantFacts().copy(maximumGopByteSize = known(Long.MAX_VALUE))).outcome)
  }

  @Test fun highTierHasNoInventedPerGopCeiling() {
    for (bitrate in listOf(MediaFact.Unknown, known(Long.MAX_VALUE))) {
      val facts = compliantFacts(dimensions = Dimensions(2560, 1440)).copy(
        averageBitrate = known(20_000_000L), maximumGopBitrate = bitrate)
      assertEquals(PolicyStatus.Compliant, evaluate(facts, MaximumResolution.Preset2560x1440).outcome)
      assertEquals(setOf(PolicyRequirement.AverageBitrate), evaluate(facts.copy(averageBitrate = known(20_000_001L)),
        MaximumResolution.Preset2560x1440).nonCompliantRequirements)
    }
  }

  @Test fun highSelectionUsesLowerTierThrough2048Inclusive() {
    for (resolution in listOf(MaximumResolution.Preset2560x1440, MaximumResolution.Preset3840x2160)) {
      for (longSide in listOf(1920, 2048, 2049)) {
        val facts = compliantFacts(dimensions = Dimensions(1152, longSide)).copy(frameRate = known(120.0),
          averageBitrate = known(15_000_000L), maximumGopBitrate = known(16_000_001L), maximumKeyframeIntervalSeconds = known(15.0))
        val expected = if (longSide <= 2048) setOf(PolicyRequirement.AverageBitrate, PolicyRequirement.MaximumGopBitrate)
          else setOf(PolicyRequirement.FrameRate, PolicyRequirement.KeyframeInterval)
        assertEquals("$resolution $longSide", expected, evaluate(facts, resolution).nonCompliantRequirements)
      }
    }
  }

  @Test fun sourceLimitsAreOrientationNeutral() {
    for ((resolution, maximum) in listOf(MaximumResolution.Default to 2048, MaximumResolution.Preset3840x2160 to 4096)) {
      for (portrait in listOf(false, true)) {
        fun facts(longSide: Int) = compliantFacts(dimensions = if (portrait) Dimensions(1080, longSide) else Dimensions(longSide, 1080))
        assertEquals(PolicyStatus.Compliant, evaluate(facts(maximum), resolution).checks[PolicyRequirement.VideoResolution])
        assertEquals(PolicyStatus.NonCompliant, evaluate(facts(maximum + 1), resolution).checks[PolicyRequirement.VideoResolution])
      }
    }
  }

  @Test fun generatedOutputMustFitBothAxesOfSelectedBounds() {
    for ((resolution, source) in listOf(MaximumResolution.Default to Dimensions(2048, 1152),
      MaximumResolution.Preset3840x2160 to Dimensions(2160, 4096), MaximumResolution.Default to Dimensions(1920, 1920))) {
      val facts = compliantFacts(dimensions = source)
      assertEquals(PolicyStatus.Compliant, evaluate(facts, resolution).checks[PolicyRequirement.VideoResolution])
      assertEquals(PolicyStatus.NonCompliant, evaluator.evaluate(facts, selection(resolution), MediaRole.GeneratedOutput)
        .checks[PolicyRequirement.VideoResolution])
    }
    assertEquals(PolicyStatus.Compliant, evaluator.evaluate(compliantFacts(dimensions = Dimensions(1080, 1920)),
      selection(), MediaRole.GeneratedOutput).checks[PolicyRequirement.VideoResolution])
  }

  @Test fun pixelFormatsAreCodecAware() {
    for (codec in VideoCodec.entries) for (depth in listOf(8, 10, 12)) for (chroma in ChromaSubsampling.entries) {
      val expected = if (chroma == ChromaSubsampling.Yuv420 &&
        (codec == VideoCodec.H264 && depth == 8 || codec == VideoCodec.Hevc && depth in listOf(8, 10))) {
        PolicyStatus.Compliant
      } else PolicyStatus.NonCompliant
      assertEquals("$codec $depth $chroma", expected, evaluate(compliantFacts(codec, pixelFormat = PixelFormat(depth, chroma)))
        .checks[PolicyRequirement.PixelFormat])
    }
  }

  @Test fun hdrRequiresHevcTenBit420() {
    for (range in listOf(DynamicRange.Hlg, DynamicRange.Pq)) {
      assertEquals(PolicyStatus.Compliant, evaluate(hdrFacts(range)).checks[PolicyRequirement.DynamicRange])
      assertEquals(PolicyStatus.NonCompliant, evaluate(hdrFacts(range).copy(videoCodec = known(VideoCodec.H264)))
        .checks[PolicyRequirement.DynamicRange])
      assertEquals(PolicyStatus.NonCompliant, evaluate(hdrFacts(range).copy(pixelFormat = known(PixelFormat(8, ChromaSubsampling.Yuv420))))
        .checks[PolicyRequirement.DynamicRange])
      assertEquals(PolicyStatus.Unknown, evaluate(hdrFacts(range).copy(pixelFormat = MediaFact.Unknown)).checks[PolicyRequirement.DynamicRange])
      assertEquals(PolicyStatus.Unknown, evaluate(hdrFacts(range).copy(videoCodec = MediaFact.Unknown)).checks[PolicyRequirement.DynamicRange])
    }
    for (range in listOf(DynamicRange.DolbyVision, DynamicRange.OtherHdr)) {
      assertEquals(PolicyStatus.NonCompliant, evaluate(hdrFacts(range)).checks[PolicyRequirement.DynamicRange])
    }
  }

  @Test fun audioAcceptsNoAudioAndAacMonoStereoSurround() {
    assertEquals(PolicyStatus.Compliant, evaluate(compliantFacts().copy(audioTracks = known(emptyList()))).outcome)
    for (layout in AudioChannelLayout.entries) {
      val expected = if (layout == AudioChannelLayout.Other) PolicyStatus.NonCompliant else PolicyStatus.Compliant
      assertEquals(expected, evaluate(compliantFacts().copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.Aac(layout)))))))
        .checks[PolicyRequirement.Audio])
    }
    assertEquals(PolicyStatus.NonCompliant, evaluate(compliantFacts().copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec))))))
      .checks[PolicyRequirement.Audio])
  }

  @Test fun multipleAudioTracksRemainUnknownEvenWithKnownNonAac() {
    val facts = compliantFacts().copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec)), AudioTrack())))
    assertEquals(setOf(PolicyRequirement.Audio), evaluate(facts).unknownRequirements)
    assertTrue(evaluate(facts).nonCompliantRequirements.isEmpty())
  }

  @Test fun gopAndEditListRejectKnownUnsupportedValues() {
    for (gop in listOf(GopStructure.Open, GopStructure.ClosedWithoutIdr)) {
      assertEquals(setOf(PolicyRequirement.GopStructure, PolicyRequirement.EditList), evaluate(compliantFacts()
        .copy(gopStructure = known(gop), editList = known(EditList.Complex))).nonCompliantRequirements)
    }
    assertEquals(PolicyStatus.Compliant, evaluate(compliantFacts().copy(editList = known(EditList.None))).outcome)
    assertEquals(PolicyStatus.NonCompliant, evaluate(compliantFacts(VideoCodec.Other)).checks[PolicyRequirement.VideoCodec])
  }

  @Test fun unknownFactsAreNotZeroAndKnownViolationWins() {
    assertEquals(PolicyRequirement.entries.toSet(), evaluate(MediaFacts()).unknownRequirements)
    assertEquals(PolicyStatus.Unknown, evaluate(MediaFacts()).outcome)
    val facts = compliantFacts().copy(averageBitrate = known(8_000_001L), maximumGopBitrate = MediaFact.Unknown)
    assertEquals(PolicyStatus.NonCompliant, evaluate(facts).outcome)
    assertEquals(setOf(PolicyRequirement.MaximumGopBitrate), evaluate(facts).unknownRequirements)
    assertEquals(setOf(PolicyRequirement.AverageBitrate), evaluate(facts).nonCompliantRequirements)
  }

  @Test fun invalidMeasurementSentinelsRemainUnknown() {
    for (number in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      val facts = compliantFacts().copy(displayDimensions = known(Dimensions(0, 1080)), frameRate = known(number),
        averageBitrate = known(0L), maximumGopBitrate = known(-1L), maximumKeyframeIntervalSeconds = known(number))
      assertEquals(setOf(PolicyRequirement.VideoResolution, PolicyRequirement.FrameRate, PolicyRequirement.AverageBitrate,
        PolicyRequirement.MaximumGopBitrate, PolicyRequirement.KeyframeInterval), evaluate(facts).unknownRequirements)
      assertTrue(evaluate(facts).nonCompliantRequirements.isEmpty())
    }
  }

  @Test fun unknownSizeRequiresAgreementBetweenPossibleTiers() {
    for (resolution in listOf(MaximumResolution.Preset2560x1440, MaximumResolution.Preset3840x2160)) {
      for (dimensions in listOf(MediaFact.Unknown, known(Dimensions(0, 1080)))) {
        val facts = compliantFacts().copy(displayDimensions = dimensions)
        for ((rate, expected) in listOf(4.999 to PolicyStatus.NonCompliant, 5.0 to PolicyStatus.Compliant,
          60.0 to PolicyStatus.Compliant, 90.0 to PolicyStatus.Unknown, 120.0 to PolicyStatus.Unknown,
          120.001 to PolicyStatus.NonCompliant)) {
          assertEquals(expected, evaluate(facts.copy(frameRate = known(rate)), resolution).checks[PolicyRequirement.FrameRate])
        }
        for ((bitrate, expected) in listOf(8_000_000L to PolicyStatus.Compliant, 15_000_000L to PolicyStatus.Unknown,
          20_000_000L to PolicyStatus.Unknown, 20_000_001L to PolicyStatus.NonCompliant)) {
          assertEquals(expected, evaluate(facts.copy(averageBitrate = known(bitrate)), resolution).checks[PolicyRequirement.AverageBitrate])
        }
        assertEquals(PolicyStatus.Unknown, evaluate(facts.copy(maximumGopBitrate = MediaFact.Unknown), resolution)
          .checks[PolicyRequirement.MaximumGopBitrate])
        assertEquals(PolicyStatus.Unknown, evaluate(facts.copy(maximumGopBitrate = known(16_000_001L)), resolution)
          .checks[PolicyRequirement.MaximumGopBitrate])
        assertEquals(PolicyStatus.Compliant, evaluate(facts.copy(maximumGopBitrate = known(16_000_000L)), resolution)
          .checks[PolicyRequirement.MaximumGopBitrate])
        for ((codec, lowLimit, highLimit) in listOf(Triple(VideoCodec.H264, 20.0, 10.0), Triple(VideoCodec.Hevc, 10.0, 6.0))) {
          assertEquals(PolicyStatus.Compliant, evaluate(facts.copy(videoCodec = known(codec),
            maximumKeyframeIntervalSeconds = known(highLimit)), resolution).checks[PolicyRequirement.KeyframeInterval])
          assertEquals(PolicyStatus.Unknown, evaluate(facts.copy(videoCodec = known(codec),
            maximumKeyframeIntervalSeconds = known(lowLimit)), resolution).checks[PolicyRequirement.KeyframeInterval])
          assertEquals(PolicyStatus.NonCompliant, evaluate(facts.copy(videoCodec = known(codec),
            maximumKeyframeIntervalSeconds = known(lowLimit + 0.001)), resolution).checks[PolicyRequirement.KeyframeInterval])
        }
      }
    }
  }

  private fun evaluate(facts: MediaFacts, resolution: MaximumResolution = MaximumResolution.Default) =
    evaluator.evaluate(facts, selection(resolution))
}
