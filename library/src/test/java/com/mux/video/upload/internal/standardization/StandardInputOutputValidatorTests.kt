package com.mux.video.upload.internal.standardization

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class StandardInputOutputValidatorTests {
  private val source = compliantFacts().copy(cadence = known(Cadence.Constant))
  private val plan = (StandardInputPlanner().plan(source.copy(averageBitrate = known(9_000_000L)),
    options(), fullCapabilities()).action as StandardInputAction.Convert).conversion
  private val output = source.copy(encodedDimensions = known(plan.outputDimensions), rotationDegrees = known(0))
  private fun timeline(duration: Double = 3.0, offset: AudioVideoStartOffset = AudioVideoStartOffset.Seconds(0.0)) =
    StandardInputTimelineFacts(known(duration), known(offset), known(listOf(0.0, 1.0, 2.0)), known(duration), known(duration), known(90000L))
  private val validator = StandardInputOutputValidator()
  private fun validate(facts: MediaFacts = output, input: MediaFacts = source,
    sourceTime: StandardInputTimelineFacts = timeline(), outputTime: StandardInputTimelineFacts = timeline(),
    conversion: StandardInputConversion = plan) = validator.validateFacts(facts, input, sourceTime, outputTime, conversion)
  private fun reason(result: StandardInputOutputValidation) = (result as StandardInputOutputValidation.Rejected).reason
  private fun mismatch(field: OutputExpectation, result: StandardInputOutputValidation) {
    assertTrue((reason(result) as OutputRejection.DoesNotMatchPlan).expectations.contains(field))
  }

  @Test fun acceptsCompliantOutputAndPublishesEffectiveTimeline() {
    val accepted = validate() as StandardInputOutputValidation.Accepted
    assertEquals(known(3.0), accepted.facts.durationSeconds)
    assertEquals(known(0.0), accepted.facts.audioVideoStartOffsetSeconds)
  }
  @Test fun everyRequiredUnknownPolicyFactRejectsOutput() {
    for (facts in listOf(output.copy(videoCodec = MediaFact.Unknown), output.copy(displayDimensions = MediaFact.Unknown),
      output.copy(frameRate = MediaFact.Unknown), output.copy(averageBitrate = MediaFact.Unknown),
      output.copy(maximumGopBitrate = MediaFact.Unknown), output.copy(maximumKeyframeIntervalSeconds = MediaFact.Unknown),
      output.copy(gopStructure = MediaFact.Unknown), output.copy(pixelFormat = MediaFact.Unknown),
      output.copy(dynamicRange = MediaFact.Unknown), output.copy(audioTracks = MediaFact.Unknown),
      output.copy(editList = MediaFact.Unknown))) {
      assertTrue(reason(validate(facts)) is OutputRejection.InsufficientPolicyEvidence)
    }
  }
  @Test fun rejectsKnownViolationsIncludingOpenGopAndComplexEdits() {
    for (facts in listOf(output.copy(averageBitrate = known(9_000_000L)), output.copy(gopStructure = known(GopStructure.Open)),
      output.copy(maximumKeyframeIntervalSeconds = known(21.0)), output.copy(editList = known(EditList.Complex))))
      assertTrue(reason(validate(facts)) is OutputRejection.NonCompliant)
  }
  @Test fun rejectsCodecFallbackEvenIfOtherwiseCompliant() {
    mismatch(OutputExpectation.VideoCodec, validate(output.copy(videoCodec = known(VideoCodec.Hevc))))
  }
  @Test fun requiresSingleVideoExactEncodedAndDisplayDimensionsAndBakedOrientation() {
    mismatch(OutputExpectation.VideoPresence, validate(output.copy(videoTrackCount = known(0))))
    mismatch(OutputExpectation.VideoPresence, validate(output.copy(videoTrackCount = known(2))))
    for (facts in listOf(output.copy(encodedDimensions = known(Dimensions(1080, 1920))),
      output.copy(displayDimensions = known(Dimensions(1918, 1080))), output.copy(rotationDegrees = known(180))))
      mismatch(OutputExpectation.DimensionsAndOrientation, validate(facts))
    assertTrue(reason(validate(output.copy(encodedDimensions = MediaFact.Unknown))) is OutputRejection.InsufficientPlanEvidence)
  }
  @Test fun compliantButUnplannedBitDepthRejects() {
    val hevc = plan.copy(outputCodec = VideoCodec.Hevc, outputPixelFormat = PixelFormat(10, ChromaSubsampling.Yuv420))
    mismatch(OutputExpectation.PixelFormat, validate(output.copy(videoCodec = known(VideoCodec.Hevc)), conversion = hevc))
  }
  @Test fun toneMappingMustProduceSdr() {
    val hevc = plan.copy(outputCodec = VideoCodec.Hevc, sourceDynamicRange = DynamicRange.Hlg, toneMapsToSdr = true)
    mismatch(OutputExpectation.DynamicRange, validate(output.copy(videoCodec = known(VideoCodec.Hevc),
      pixelFormat = known(PixelFormat(10, ChromaSubsampling.Yuv420)), dynamicRange = known(DynamicRange.Hlg)), conversion = hevc))
  }
  @Test fun expectedAudioCannotDisappearOrAppearAndKnownLayoutIsRetained() {
    mismatch(OutputExpectation.Audio, validate(output.copy(audioTracks = known(emptyList()))))
    mismatch(OutputExpectation.Audio, validate(conversion = plan.copy(outputAudio = OutputAudio.None)))
    mismatch(OutputExpectation.Audio, validate(output.copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Mono))))))))
    assertTrue(reason(validate(input = source.copy(audioTracks = MediaFact.Unknown),
      conversion = plan.copy(outputAudio = OutputAudio.AacFromFirstTrackIfPresent))) is OutputRejection.InsufficientPlanEvidence)
    assertTrue(validate(output.copy(audioTracks = known(emptyList())), source.copy(audioTracks = known(emptyList())),
      timeline(offset = AudioVideoStartOffset.NotApplicable), timeline(offset = AudioVideoStartOffset.NotApplicable),
      plan.copy(outputAudio = OutputAudio.AacFromFirstTrackIfPresent)) is StandardInputOutputValidation.Accepted)
  }
  @Test fun durationToleranceIncludes50msBoundaryAndUsesOneFrameAtLowRate() {
    assertTrue(validate(outputTime = timeline(3.05)) is StandardInputOutputValidation.Accepted)
    mismatch(OutputExpectation.Duration, validate(outputTime = timeline(3.050001)))
    val low = plan.copy(outputFrameRate = 5.0)
    assertTrue(validate(output.copy(frameRate = known(5.0)), outputTime = timeline(3.2), conversion = low) is StandardInputOutputValidation.Accepted)
    mismatch(OutputExpectation.Duration, validate(output.copy(frameRate = known(5.0)), outputTime = timeline(3.200001), conversion = low))
  }
  @Test fun matchingFirstTrackFormatDoesNotProveMuxAudioSelection() {
    val multi = source.copy(audioTracks = known(listOf(
      AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Stereo))),
      AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Mono))))))
    val rejection = reason(validate(input = multi)) as OutputRejection.InsufficientPlanEvidence
    assertTrue(rejection.expectations.contains(OutputExpectation.Audio))
  }
  @Test fun avStartToleranceIs50msAndAbsenceIsExplicit() {
    assertTrue(validate(outputTime = timeline(offset = AudioVideoStartOffset.Seconds(-0.05))) is StandardInputOutputValidation.Accepted)
    mismatch(OutputExpectation.AudioVideoStartOffset, validate(outputTime = timeline(offset = AudioVideoStartOffset.Seconds(0.050001))))
    mismatch(OutputExpectation.AudioVideoStartOffset, validate(outputTime = timeline(offset = AudioVideoStartOffset.NotApplicable)))
  }
  @Test fun unknownOrInvalidTimelineNeverAccepts() {
    for (time in listOf(StandardInputTimelineFacts(), timeline(0.0), timeline(Double.NaN), timeline(Double.POSITIVE_INFINITY),
      timeline(offset = AudioVideoStartOffset.Seconds(Double.NaN)))) {
      assertTrue(reason(validate(sourceTime = time)) is OutputRejection.InsufficientPlanEvidence)
      assertTrue(reason(validate(outputTime = time)) is OutputRejection.InsufficientPlanEvidence)
    }
  }
  @Test fun aspectAlignmentUsesIntersectionOfBothAxisScaleRanges() {
    assertTrue(StandardInputOutputValidator.preservesAspectRatio(Dimensions(1920, 1080), Dimensions(1282, 718)))
    assertFalse(StandardInputOutputValidator.preservesAspectRatio(Dimensions(1920, 1080), Dimensions(1283, 717)))
    assertFalse(StandardInputOutputValidator.preservesAspectRatio(Dimensions(0, 1080), Dimensions(1280, 720)))
    mismatch(OutputExpectation.DimensionsAndOrientation, validate(conversion = plan.copy(sourceDisplayDimensions = Dimensions(1000, 1000))))
  }
  @Test fun preserveTimestampsChecksEveryPresentationTimeWithoutRejectingBFrameDecodeOrder() {
    assertTrue(validate(output.copy(timestamps = known(TimestampFacts(0.0, 2.0, 3, false)))) is StandardInputOutputValidation.Accepted)
    mismatch(OutputExpectation.Timestamps, validate(outputTime = timeline().copy(videoPresentationSeconds = known(listOf(0.0, 1.01, 2.0)))))
    assertTrue(reason(validate(sourceTime = timeline().copy(videoPresentationSeconds = MediaFact.Unknown))) is OutputRejection.InsufficientPlanEvidence)
  }
  @Test fun resamplingRequiresConstantCadenceAndPlannedRate() {
    val constant = plan.copy(outputCadence = OutputCadence.ConstantFrameRate)
    assertTrue(validate(conversion = constant) is StandardInputOutputValidation.Accepted)
    mismatch(OutputExpectation.Cadence, validate(output.copy(cadence = known(Cadence.Variable)), conversion = constant))
    mismatch(OutputExpectation.Cadence, validate(output.copy(frameRate = known(29.97)), conversion = constant))
  }
  @Test fun emptyMissingAndAudioOnlyFilesRejectAndCancellationWins() {
    val file = File.createTempFile("validation", ".mp4")
    try {
      assertEquals(OutputRejection.EmptyOrUnreadable, reason(validator.validateGeneratedOutput(file, source, timeline(), plan)))
      assertEquals(StandardInputOutputValidation.Cancelled, validator.validateGeneratedOutput(file, source, timeline(), plan) { true })
      file.writeBytes(byteArrayOf(1))
      val unreadable = StandardInputOutputValidator(inspectMetadata = { throw CancellationException() })
      assertEquals(StandardInputOutputValidation.Cancelled, unreadable.validateGeneratedOutput(file, source, timeline(), plan))
    } finally { file.delete() }
  }
  @Test fun partialScansNeverReachComparisonAndLateCancellationWins() {
    val file = File.createTempFile("validation", ".mp4")
    val metadata = MediaMetadataInspection(known(ContainerKind.IsoBaseMedia), emptyList(), MediaFact.Unknown, output, 0)
    try {
      file.writeBytes(byteArrayOf(1))
      for (status in SampleScanStatus.entries.filter { it != SampleScanStatus.Complete }) {
        val v = StandardInputOutputValidator(inspectMetadata = { MetadataInspectionResult.Success(metadata) },
          inspectTimeline = { _, _, _ -> MediaSampleInspection(output, status, timeline = timeline()) })
        val result = v.validateGeneratedOutput(file, source, timeline(), plan)
        if (status == SampleScanStatus.Cancelled) assertEquals(StandardInputOutputValidation.Cancelled, result)
        else assertEquals(OutputRejection.TimelineInspection(status), reason(result))
      }
      var cancelled = false
      val v = StandardInputOutputValidator(inspectMetadata = { cancelled = true; MetadataInspectionResult.Failure(MetadataFailure.Malformed) })
      assertEquals(StandardInputOutputValidation.Cancelled, v.validateGeneratedOutput(file, source, timeline(), plan) { cancelled })
    } finally { file.delete() }
  }
  @Test fun changedFileCannotSelectGeneratedBytesAfterCompleteInspection() {
    val file = File.createTempFile("validation", ".mp4")
    val metadata = MediaMetadataInspection(known(ContainerKind.IsoBaseMedia), emptyList(), MediaFact.Unknown, output, 0)
    fun validator(timelineRead: () -> MediaSampleInspection) = StandardInputOutputValidator(
      inspectMetadata = { MetadataInspectionResult.Success(metadata) },
      inspectTimeline = { _, _, _ -> timelineRead() })
    try {
      file.writeBytes(byteArrayOf(1))
      val changed = validator { file.appendBytes(byteArrayOf(2)); MediaSampleInspection(output, SampleScanStatus.Complete, timeline = timeline()) }
      assertEquals(OutputRejection.ChangedDuringInspection, reason(changed.validateGeneratedOutput(file, source, timeline(), plan)))
      val accepted = validator { MediaSampleInspection(output, SampleScanStatus.Complete, timeline = timeline()) }.validateGeneratedOutput(file, source, timeline(), plan)
      assertTrue(accepted is StandardInputOutputValidation.Accepted)
    } finally { file.delete() }
  }

  @Test fun shorterVideoOrAudioCannotHideBehindUnchangedAssetDuration() {
    val constant = plan.copy(outputCadence = OutputCadence.ConstantFrameRate)
    mismatch(OutputExpectation.Duration, validate(outputTime = timeline().copy(videoDurationSeconds = known(1.0)), conversion = constant))
    mismatch(OutputExpectation.Duration, validate(outputTime = timeline().copy(firstAudioDurationSeconds = known(1.0)), conversion = constant))
    assertTrue(reason(validate(outputTime = timeline().copy(videoDurationSeconds = MediaFact.Unknown))) is OutputRejection.InsufficientPlanEvidence)
    assertTrue(reason(validate(sourceTime = timeline().copy(firstAudioDurationSeconds = MediaFact.Unknown))) is OutputRejection.InsufficientPlanEvidence)
  }


  @Test fun timestampPreservationAllowsOnlyProvenOutputTickRounding() {
    val rate = 24000.0 / 1001
    val times = List(72) { it * 1001.0 / 24000 }
    val rounded = times.map { kotlin.math.round(kotlin.math.floor(it * 1e6) * 0.09) / 90000 }
    val inputTime = timeline().copy(videoPresentationSeconds = known(times))
    val outputTime = timeline().copy(videoPresentationSeconds = known(rounded))
    val conversion = plan.copy(outputFrameRate = rate)
    val facts = output.copy(frameRate = known(rate))
    assertTrue(validate(facts, sourceTime = inputTime, outputTime = outputTime, conversion = conversion)
      is StandardInputOutputValidation.Accepted)
    val drifted = rounded.toMutableList().apply { this[10] += 0.000020 }
    mismatch(OutputExpectation.Timestamps, validate(facts, sourceTime = inputTime,
      outputTime = outputTime.copy(videoPresentationSeconds = known(drifted)), conversion = conversion))
    for (scale in listOf(MediaFact.Unknown, known(0L), known(-1L)))
      assertTrue(reason(validate(facts, sourceTime = inputTime,
        outputTime = outputTime.copy(videoTimescale = scale), conversion = conversion)) is OutputRejection.InsufficientPlanEvidence)
  }

  @Test fun completedTimelineWithoutSampleProofCannotAcceptOutput() {
    val file = File.createTempFile("validation", ".mp4")
    try {
      file.writeBytes(byteArrayOf(1))
      val metadata = MediaMetadataInspection(known(ContainerKind.IsoBaseMedia), emptyList(), MediaFact.Unknown, output, 0)
      val validator = StandardInputOutputValidator(inspectMetadata = { MetadataInspectionResult.Success(metadata) },
        inspectTimeline = { _, _, _ -> MediaSampleInspection(MediaFacts(), SampleScanStatus.Complete, timeline = timeline()) })
      assertTrue(reason(validator.validateGeneratedOutput(file, source, timeline(), plan))
        is OutputRejection.InsufficientPolicyEvidence)
    } finally { file.delete() }
  }
}
