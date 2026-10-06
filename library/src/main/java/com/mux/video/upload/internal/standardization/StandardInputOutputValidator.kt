package com.mux.video.upload.internal.standardization

import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs

internal enum class OutputExpectation {
  VideoPresence, VideoCodec, DimensionsAndOrientation, PixelFormat, DynamicRange,
  Audio, Duration, AudioVideoStartOffset, Cadence, Timestamps,
}

internal sealed interface OutputRejection {
  data object EmptyOrUnreadable : OutputRejection
  data object ChangedDuringInspection : OutputRejection
  data class Metadata(val reason: MetadataFailure) : OutputRejection
  data class TimelineInspection(val status: SampleScanStatus) : OutputRejection
  data class NonCompliant(val requirements: Set<PolicyRequirement>) : OutputRejection
  data class InsufficientPolicyEvidence(val requirements: Set<PolicyRequirement>) : OutputRejection
  data class InsufficientPlanEvidence(val expectations: Set<OutputExpectation>) : OutputRejection
  data class DoesNotMatchPlan(val expectations: Set<OutputExpectation>) : OutputRejection
}

/** Rejection permits original-file fallback; Cancelled must terminate preparation without upload. */
internal sealed interface StandardInputOutputValidation {
  data class Accepted(val facts: MediaFacts, val evaluation: PolicyEvaluation) : StandardInputOutputValidation
  data class Rejected(val reason: OutputRejection, val evaluation: PolicyEvaluation? = null) : StandardInputOutputValidation
  data object Cancelled : StandardInputOutputValidation
}

/** Internal seam for the later adapter; never selects/deletes payloads or starts an upload. */
internal class StandardInputOutputValidator(
  private val evaluator: StandardInputPolicyEvaluator = StandardInputPolicyEvaluator(),
  private val inspectMetadata: (File) -> MetadataInspectionResult = MediaMetadataInspector()::inspect,
  private val inspectTimeline: (File, MediaMetadataInspection, () -> Boolean) -> TimelineInspection =
    { file, metadata, cancelled -> StandardInputTimelineInspector().inspect(file, metadata, cancelled) },
) {
  fun validateGeneratedOutput(file: File, source: MediaFacts, sourceTimeline: StandardInputTimelineFacts,
    conversion: StandardInputConversion,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): StandardInputOutputValidation {
    fun rejected(reason: OutputRejection) = StandardInputOutputValidation.Rejected(reason)
    fun failedInspection() = if (isCancelled()) StandardInputOutputValidation.Cancelled else rejected(OutputRejection.EmptyOrUnreadable)
    return try {
      if (isCancelled()) return StandardInputOutputValidation.Cancelled
      if (!file.isFile || !file.canRead() || file.length() == 0L) return rejected(OutputRejection.EmptyOrUnreadable)
      val initialSize = file.length()
      val initialModified = file.lastModified()
      val metadataResult = inspectMetadata(file)
      if (isCancelled()) return StandardInputOutputValidation.Cancelled
      val metadata = when (metadataResult) {
        is MetadataInspectionResult.Success -> metadataResult.inspection
        is MetadataInspectionResult.Failure -> return rejected(OutputRejection.Metadata(metadataResult.reason))
      }
      val timeline = inspectTimeline(file, metadata, isCancelled)
      if (isCancelled() || timeline.status == SampleScanStatus.Cancelled) return StandardInputOutputValidation.Cancelled
      if (timeline.status != SampleScanStatus.Complete) return rejected(OutputRejection.TimelineInspection(timeline.status))
      val duration = timeline.timeline.videoDurationSeconds.valueOrNull
      if (duration == null || !duration.isFinite() || duration <= 0)
        return rejected(OutputRejection.InsufficientPlanEvidence(setOf(OutputExpectation.Duration)))
      val facts = timeline.sampleFacts ?: return rejected(OutputRejection.TimelineInspection(SampleScanStatus.Unreadable))
      if (file.length() != initialSize || file.lastModified() != initialModified)
        return rejected(OutputRejection.ChangedDuringInspection)
      val result = validateFacts(facts, source, sourceTimeline, timeline.timeline, conversion)
      if (isCancelled()) StandardInputOutputValidation.Cancelled else result
    } catch (_: CancellationException) { StandardInputOutputValidation.Cancelled }
      catch (_: LinkageError) { failedInspection() }
      catch (_: Exception) { failedInspection() }
  }

  fun validateFacts(output: MediaFacts, source: MediaFacts, sourceTimeline: StandardInputTimelineFacts,
    outputTimeline: StandardInputTimelineFacts, conversion: StandardInputConversion): StandardInputOutputValidation {
    val evaluation = evaluator.evaluate(output, conversion.selection, MediaRole.GeneratedOutput)
    fun rejected(reason: OutputRejection) = StandardInputOutputValidation.Rejected(reason, evaluation)
    if (evaluation.nonCompliantRequirements.isNotEmpty())
      return rejected(OutputRejection.NonCompliant(evaluation.nonCompliantRequirements))
    if (evaluation.unknownRequirements.isNotEmpty())
      return rejected(OutputRejection.InsufficientPolicyEvidence(evaluation.unknownRequirements))
    val missing = mutableSetOf<OutputExpectation>()
    val mismatches = mutableSetOf<OutputExpectation>()
    fun <T> expect(fact: MediaFact<T>, expected: T, field: OutputExpectation) {
      val actual = fact.valueOrNull
      if (actual == null) missing.add(field) else if (actual != expected) mismatches.add(field)
    }
    expect(output.videoTrackCount, 1, OutputExpectation.VideoPresence)
    expect(output.videoCodec, conversion.outputCodec, OutputExpectation.VideoCodec)
    // The plan's dimensions are display-oriented. The adapter must bake orientation into pixels.
    expect(output.encodedDimensions, conversion.outputDimensions, OutputExpectation.DimensionsAndOrientation)
    expect(output.displayDimensions, conversion.outputDimensions, OutputExpectation.DimensionsAndOrientation)
    expect(output.rotationDegrees, 0, OutputExpectation.DimensionsAndOrientation)
    expect(output.pixelFormat, conversion.outputPixelFormat, OutputExpectation.PixelFormat)
    expect(output.dynamicRange, conversion.outputDynamicRange, OutputExpectation.DynamicRange)
    if (!preservesAspectRatio(conversion.sourceDisplayDimensions, conversion.outputDimensions))
      mismatches.add(OutputExpectation.DimensionsAndOrientation)

    val sourceAudio = source.audioTracks.valueOrNull
    val outputAudio = output.audioTracks.valueOrNull
    val wantsAudio = when (conversion.outputAudio) {
      OutputAudio.None -> false
      OutputAudio.AacFromFirstTrack -> true
      OutputAudio.AacFromFirstTrackIfPresent -> sourceAudio?.isNotEmpty()
    }
    if (wantsAudio == null || outputAudio == null) missing.add(OutputExpectation.Audio)
    else if (outputAudio.size != if (wantsAudio) 1 else 0) mismatches.add(OutputExpectation.Audio)
    else if (wantsAudio) {
      val sourceFormat = sourceAudio?.firstOrNull()?.format?.valueOrNull
      if (sourceFormat is AudioFormat.Aac && outputAudio.single().format.valueOrNull != sourceFormat)
        mismatches.add(OutputExpectation.Audio)
    }
    val rate = output.frameRate.valueOrNull?.takeIf { it.isFinite() && it > 0 }
    if (rate == null) missing.add(OutputExpectation.Cadence)
    else if (!conversion.outputFrameRate.isFinite() || conversion.outputFrameRate <= 0 ||
      abs(rate - conversion.outputFrameRate) > conversion.outputFrameRate * 0.001)
      mismatches.add(OutputExpectation.Cadence)
    when (conversion.outputCadence) {
      OutputCadence.ConstantFrameRate -> expect(output.cadence, Cadence.Constant, OutputExpectation.Cadence)
      OutputCadence.PreserveSourceTimestamps -> {
        val cadence = source.cadence.valueOrNull
        if (cadence == null) missing.add(OutputExpectation.Cadence)
        else expect(output.cadence, cadence, OutputExpectation.Cadence)
        val sourceTimes = sourceTimeline.videoPresentationSeconds.valueOrNull
        val outputTimes = outputTimeline.videoPresentationSeconds.valueOrNull
        if (sourceTimes == null || outputTimes == null || sourceTimes.isEmpty() || outputTimes.isEmpty())
          missing.add(OutputExpectation.Timestamps)
        else {
          val timescale = outputTimeline.videoTimescale.valueOrNull?.takeIf { it > 0 }
          if (timescale == null) missing.add(OutputExpectation.Timestamps)
          else if (sourceTimes.size != outputTimes.size || sourceTimes.indices.any {
            // Extractor/codec timestamps truncate to microseconds; the muxer rounds to output ticks.
            !sourceTimes[it].isFinite() || !outputTimes[it].isFinite() ||
              abs(sourceTimes[it] - outputTimes[it]) > 1e-6 + 0.5 / timescale + 1e-12
          }) mismatches.add(OutputExpectation.Timestamps)
        }
      }
    }
    fun compareDuration(sourceFact: MediaFact<Double>, outputFact: MediaFact<Double>) {
      val sourceDuration = sourceFact.valueOrNull?.takeIf { it.isFinite() && it > 0 }
      val outputDuration = outputFact.valueOrNull?.takeIf { it.isFinite() && it > 0 }
      if (sourceDuration == null || outputDuration == null || rate == null) missing.add(OutputExpectation.Duration)
      else if (abs(sourceDuration - outputDuration) > maxOf(MINIMUM_DURATION_DELTA, 1 / rate) + 1e-12)
        mismatches.add(OutputExpectation.Duration)
    }
    compareDuration(sourceTimeline.durationSeconds, outputTimeline.durationSeconds)
    compareDuration(sourceTimeline.videoDurationSeconds, outputTimeline.videoDurationSeconds)
    if (wantsAudio == true) compareDuration(sourceTimeline.firstAudioDurationSeconds, outputTimeline.firstAudioDurationSeconds)
    val sourceOffset = sourceTimeline.audioVideoStartOffset.valueOrNull
    val outputOffset = outputTimeline.audioVideoStartOffset.valueOrNull
    when {
      sourceOffset == null || outputOffset == null -> missing.add(OutputExpectation.AudioVideoStartOffset)
      sourceOffset is AudioVideoStartOffset.Seconds && outputOffset is AudioVideoStartOffset.Seconds -> {
        if (!sourceOffset.value.isFinite() || !outputOffset.value.isFinite()) missing.add(OutputExpectation.AudioVideoStartOffset)
        else if (abs(sourceOffset.value - outputOffset.value) > MAXIMUM_AV_START_DELTA + 1e-12)
          mismatches.add(OutputExpectation.AudioVideoStartOffset)
      }
      sourceOffset != outputOffset -> mismatches.add(OutputExpectation.AudioVideoStartOffset)
    }
    return when {
      mismatches.isNotEmpty() -> rejected(OutputRejection.DoesNotMatchPlan(mismatches))
      missing.isNotEmpty() -> rejected(OutputRejection.InsufficientPlanEvidence(missing))
      else -> StandardInputOutputValidation.Accepted(output.copy(durationSeconds = outputTimeline.durationSeconds,
        audioVideoStartOffsetSeconds = (outputOffset as? AudioVideoStartOffset.Seconds)?.let { MediaFact.Known(it.value) }
          ?: MediaFact.Unknown), evaluation)
    }
  }

  companion object {
    const val MINIMUM_DURATION_DELTA = 0.050
    const val MAXIMUM_AV_START_DELTA = 0.050
    const val MAXIMUM_ASPECT_DIMENSION_ERROR = 2.0

    internal fun preservesAspectRatio(source: Dimensions, output: Dimensions): Boolean {
      if (!source.isValid || !output.isValid) return false
      val error = MAXIMUM_ASPECT_DIMENSION_ERROR
      val minimum = maxOf(0.0, (output.width - error) / source.width, (output.height - error) / source.height)
      val maximum = minOf((output.width + error) / source.width, (output.height + error) / source.height)
      return minimum <= maximum
    }
  }
}
