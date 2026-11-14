package com.mux.video.upload.internal.standardization

import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.internal.InputStandardization
import java.util.Locale

/**
 * One-line `standard_input event=...` records, matching the iOS SDK's diagnostic shape so either
 * platform's logs show why a file was converted or uploaded unchanged. Records carry media facts and
 * decisions only: never file paths, URLs, or platform error text.
 */
internal object StandardInputDiagnostics {
  const val TAG = "MuxUploadStandardization"

  fun inspection(role: String, status: SampleScanStatus, facts: MediaFacts, sampleCount: Int, elapsedNanos: Long) = log(
    "inspection_completed", "role" to role, "status" to status, "samples" to sampleCount,
    "scan_ms" to elapsedNanos / 1_000_000, *facts.fields())

  fun inspectionFailed() = log("standardization_failure", "failure_category" to "inspection",
    "failure_reason" to "metadata_unreadable")

  fun capabilities(targetsAvailable: Boolean, preflight: SdrPreflight?) = log("capabilities_checked",
    "targets" to if (targetsAvailable) "available" else "unavailable",
    "decoder" to (preflight?.sourceDecodable ?: "unchecked"), "audio" to (preflight?.audioPreparable ?: "unchecked"),
    "tone_mapping" to preflight?.toneMapSupported, "encoder" to (preflight?.encoder?.name ?: "none"),
    "encoder_profile" to preflight?.encoder?.profile)

  fun plan(plan: StandardInputPlan, options: InputStandardization) {
    val action = plan.action
    val fields = mutableListOf<Pair<String, Any?>>(
      "plan" to when (action) {
        is StandardInputAction.UploadOriginal -> "upload_original"
        is StandardInputAction.Convert -> "convert"
        is StandardInputAction.Fallback -> "fallback"
      },
      "plan_reason" to when (action) {
        is StandardInputAction.UploadOriginal -> action.reason.label()
        is StandardInputAction.Convert -> "noncompliant_input"
        is StandardInputAction.Fallback -> action.reason.label()
      },
      "max_resolution" to options.maximumResolution.let { "${it.width}x${it.height}" },
      "hdr_handling" to options.hdrHandling,
      "policy_outcome" to plan.evaluation.outcome,
      "noncompliant_requirements" to plan.evaluation.nonCompliantRequirements.sorted(),
      "unknown_requirements" to plan.evaluation.unknownRequirements.sorted(),
    )
    val conversion = (action as? StandardInputAction.Convert)?.conversion
      ?: ((action as? StandardInputAction.Fallback)?.reason as? FallbackReason.UnsupportedConversion)?.conversion
    if (conversion != null) fields += listOf("output_codec" to conversion.outputCodec,
      "output_resolution" to conversion.outputDimensions.label(), "output_frame_rate" to conversion.outputFrameRate.format(),
      "tone_map" to conversion.toneMapsToSdr, "remediate" to conversion.requirementsToRemediate.sorted())
    ((action as? StandardInputAction.Fallback)?.reason as? FallbackReason.UnsupportedConversion)?.let {
      fields += "missing_capabilities" to it.missingCapabilities.map { missing -> missing.label() }.sorted()
    }
    log("plan_selected", *fields.toTypedArray())
  }

  fun conversionStarted() = log("conversion_started")

  fun conversionCompleted(elapsedNanos: Long, outputBytes: Long, facts: MediaFacts) = log("conversion_completed",
    "elapsed_ms" to elapsedNanos / 1_000_000, "output_bytes" to outputBytes, *facts.fields())

  fun conversionFailed(elapsedNanos: Long, reason: SdrConversionFailure) = log("standardization_failure",
    "failure_category" to "conversion", "failure_reason" to reason, "elapsed_ms" to elapsedNanos / 1_000_000)

  fun unexpectedFailure(error: Throwable) = log("standardization_failure", "failure_category" to "unexpected",
    "failure_reason" to error.javaClass.simpleName)

  fun preparationOriginal(reason: PreparationDiagnostic) = log("preparation_original", "reason" to reason)

  fun payloadSelected(standardized: Boolean, bytes: Long) = log("payload_selected",
    "payload" to if (standardized) "standardized" else "original", "bytes" to bytes)

  private fun MediaFacts.fields(): Array<Pair<String, Any?>> = arrayOf(
    "codec" to videoCodec.label(), "profile" to videoProfile.label(),
    "resolution" to (displayDimensions.valueOrNull?.label() ?: "unknown"),
    "frame_rate" to (frameRate.valueOrNull?.format() ?: "unknown"), "cadence" to cadence.label(),
    "dynamic_range" to dynamicRange.label(), "pixel_format" to pixelFormat.valueOrNull?.let { "${it.bitDepth}bit_${it.chromaSubsampling}" },
    "average_bitrate" to averageBitrate.label(), "max_gop_bitrate" to maximumGopBitrate.label(),
    "max_keyframe_interval" to (maximumKeyframeIntervalSeconds.valueOrNull?.format() ?: "unknown"),
    "gop" to gopStructure.label(), "edit_list" to editList.label(),
    "audio" to (audioTracks.valueOrNull?.joinToString(",", "[", "]") { (it.format.valueOrNull ?: "unknown").toString() } ?: "unknown"),
  )

  private fun MediaFact<*>.label(): String = valueOrNull?.toString() ?: "unknown"
  private fun Dimensions.label() = "${width}x$height"
  private fun Double.format() = String.format(Locale.US, "%.3f", this)
  private fun OriginalReason.label(): String = when (this) {
    OriginalReason.StandardizationNotRequested -> "standardization_not_requested"
    OriginalReason.StandardInput -> "standard_input"
    OriginalReason.NoKnownStandardInputViolation -> "no_known_violation"
    is OriginalReason.PreserveHdr -> "preserve_hdr_$dynamicRange"
  }
  private fun FallbackReason.label(): String = when (this) {
    FallbackReason.InsufficientEvidence -> "insufficient_evidence"
    FallbackReason.UnsafeVideoTrackStructure -> "unsafe_video_track_structure"
    FallbackReason.UnverifiedAudioSelection -> "multiple_audio_tracks"
    is FallbackReason.UnsupportedConversion -> "unsupported_conversion"
    is FallbackReason.UnsupportedHdr -> "unsupported_hdr_$dynamicRange"
    is FallbackReason.HdrPreservationUnavailable -> "hdr_preservation_unavailable_$dynamicRange"
    is FallbackReason.NonStandardHdr -> "nonstandard_hdr_$dynamicRange"
  }
  private fun ConversionCapabilityFailure.label(): String = when (this) {
    ConversionCapabilityFailure.SourceDecode -> "source_decode"
    ConversionCapabilityFailure.SourceTimeline -> "source_timeline"
    ConversionCapabilityFailure.VideoEncode -> "video_encode"
    is ConversionCapabilityFailure.Remediation -> "remediate_$requirement"
    ConversionCapabilityFailure.AacAudioPreparation -> "aac_audio"
    ConversionCapabilityFailure.ToneMapping -> "tone_mapping"
  }

  private fun log(event: String, vararg fields: Pair<String, Any?>) {
    val message = buildString {
      append("standard_input event=").append(event)
      for ((key, value) in fields) {
        val text = when (value) {
          null -> "n/a"
          is Collection<*> -> value.joinToString(",", "[", "]")
          else -> value.toString()
        }
        append(' ').append(key).append('=').append(text.replace(' ', '_'))
      }
    }
    runCatching { MuxUploadSdk.logger.i(TAG, message) }
  }
}
