package com.mux.video.upload.internal.standardization

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.abs
import kotlin.math.ceil

internal enum class SdrConversionFailure {
  UnsupportedPlan, InsufficientSourceEvidence, CapabilityUnavailable, DiskSpace,
  Export, ForbiddenFallback, CodecChanged, OutputInvalid, SourceChanged, FileOwnership,
}

/** Encoder targets are deliberately below policy ceilings; output validation still proves compliance. */
internal data class SdrEncodingTargets(
  val videoMime: String,
  val profile: VideoProfile,
  val platformProfile: Int,
  val bitrate: Int,
  val keyframeIntervalSeconds: Int,
  val audioChannels: Int?,
  val audioSampleRate: Int?,
  val copyAac: Boolean,
  val firstAudioTrackId: String?,
) {
  fun estimatedOutputBytes(duration: Double): Long? {
    if (!duration.isFinite() || duration <= 0) return null
    val bytes = ceil(duration * (bitrate + if (audioChannels != null) 384_000 else 0) / 8 * 1.5) + 8 * 1024 * 1024
    return bytes.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
  }

  companion object {
    fun from(conversion: StandardInputConversion, metadata: MediaMetadataInspection,
      source: MediaSampleInspection): SdrEncodingTargets? {
      val facts = source.facts
      if (source.status != SampleScanStatus.Complete || conversion.toneMapsToSdr ||
        conversion.sourceDynamicRange != DynamicRange.Sdr || facts.dynamicRange != MediaFact.Known(DynamicRange.Sdr) ||
        facts.videoCodec != MediaFact.Known(conversion.sourceCodec) || facts.videoTrackCount != MediaFact.Known(1) ||
        facts.displayDimensions != MediaFact.Known(conversion.sourceDisplayDimensions) ||
        source.timeline.videoPresentationSeconds.valueOrNull.isNullOrEmpty() ||
        source.timeline.videoDurationSeconds.valueOrNull == null ||
        source.timeline.audioVideoStartOffset == MediaFact.Unknown) return null
      val expectedCodec = if (conversion.sourceCodec == VideoCodec.Hevc) VideoCodec.Hevc else VideoCodec.H264
      val size = conversion.outputDimensions
      val rate = conversion.outputFrameRate
      if (conversion.outputCodec != expectedCodec || !size.isValid || size.width % 2 != 0 || size.height % 2 != 0 ||
        size.width > conversion.sourceDisplayDimensions.width || size.height > conversion.sourceDisplayDimensions.height ||
        !size.fitsWithin(conversion.selection.generatedOutputDimensions) ||
        !StandardInputOutputValidator.preservesAspectRatio(conversion.sourceDisplayDimensions, size) ||
        !rate.isFinite() || rate !in conversion.outputPolicyLimits.frameRateRange ||
        conversion.outputPixelFormat != PixelFormat(8, ChromaSubsampling.Yuv420)) return null
      // This SDR surface path has no proof of 10-bit SDR preservation or frame duplication.
      val sourceRate = facts.frameRate.valueOrNull ?: return null
      if (conversion.outputCadence == OutputCadence.ConstantFrameRate) {
        val ratio = sourceRate / rate
        if (facts.cadence != MediaFact.Known(Cadence.Constant) || rate != 30.0 || ratio < 1 ||
          // Measured rates use microsecond-rounded timestamps; permit only rounding-scale noise.
          abs(ratio - kotlin.math.round(ratio)) > maxOf(1.0, ratio) * 1e-6) return null
      } else if (sourceRate != rate || facts.cadence == MediaFact.Unknown) return null
      val audioFacts = facts.audioTracks.valueOrNull ?: return null
      val audio = metadata.tracks.filter { it.kind == TrackKind.Audio }.sortedBy { it.containerIndex.valueOrNull ?: Int.MAX_VALUE }
      if (audio.size != audioFacts.size || audio.any { it.containerIndex == MediaFact.Unknown }) return null
      if ((conversion.outputAudio == OutputAudio.None) != audio.isEmpty() &&
        conversion.outputAudio != OutputAudio.AacFromFirstTrackIfPresent) return null
      val first = audio.firstOrNull()
      val channels = first?.audio?.channelCount?.valueOrNull
      val sampleRate = first?.audio?.sampleRate?.valueOrNull
      val copy = first?.audio?.format?.valueOrNull is AudioFormat.Aac
      val layout = (first?.audio?.format?.valueOrNull as? AudioFormat.Aac)?.layout
      if (first != null && (first.audio?.format == MediaFact.Unknown || first.sourceTrackId.valueOrNull == null || channels == null || sampleRate == null ||
          sampleRate <= 0 || (if (copy) layout !in setOf(AudioChannelLayout.Mono, AudioChannelLayout.Stereo,
            AudioChannelLayout.FivePointOne) else channels !in 1..2))) return null
      val interval = conversion.outputPolicyLimits.maximumKeyframeIntervals[expectedCodec] ?: return null
      if (!interval.isFinite() || interval < 1) return null
      return SdrEncodingTargets(
        if (expectedCodec == VideoCodec.Hevc) "video/hevc" else "video/avc",
        if (expectedCodec == VideoCodec.Hevc) VideoProfile.HevcMain else VideoProfile.H264Baseline,
        if (expectedCodec == VideoCodec.Hevc) CodecProfileLevel.HEVCProfileMain else CodecProfileLevel.AVCProfileBaseline,
        minOf(6_000_000L.takeIf { size.longSide <= 2048 } ?: 15_000_000L,
          conversion.outputPolicyLimits.maximumAverageBitrate * 3 / 4).toInt().takeIf { it > 0 } ?: return null,
        maxOf(1, (interval * 0.9).toInt()), channels, sampleRate, copy, first?.sourceTrackId?.valueOrNull?.toString(),
      )
    }
  }
}

internal data class SdrEncoderCapability(val name: String, val level: Int)

/** Bounded advertised-format guard. This does not manufacture planner/device-path proof. */
internal class SdrCapabilityPreflight {
  fun videoEncoder(conversion: StandardInputConversion, metadata: MediaMetadataInspection,
    targets: SdrEncodingTargets, isCancelled: () -> Boolean): SdrEncoderCapability? {
    val started = System.nanoTime()
    fun withinBudget() = !isCancelled() && System.nanoTime() - started < 500_000_000L
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    if (infos.size > 128 || !withinBudget()) return null
    val video = metadata.tracks.singleOrNull { it.kind == TrackKind.Video } ?: return null
    val sourceMime = video.mimeType.valueOrNull ?: return null
    val sourceSize = video.video?.encodedDimensions?.valueOrNull ?: return null
    fun supportsDecode(mime: String, format: MediaFormat): Boolean = infos.any { info ->
      withinBudget() && !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
        runCatching { info.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
    }
    val sourceFormat = MediaFormat.createVideoFormat(sourceMime, sourceSize.width, sourceSize.height)
    val sourceProfile = when (video.video.profile.valueOrNull) {
      VideoProfile.H264Baseline -> CodecProfileLevel.AVCProfileBaseline
      VideoProfile.H264Main -> CodecProfileLevel.AVCProfileMain
      VideoProfile.H264High -> CodecProfileLevel.AVCProfileHigh
      VideoProfile.HevcMain -> CodecProfileLevel.HEVCProfileMain
      VideoProfile.HevcMain10 -> CodecProfileLevel.HEVCProfileMain10
      else -> null
    }
    if (sourceProfile != null) sourceFormat.setInteger(MediaFormat.KEY_PROFILE, sourceProfile)
    if (!supportsDecode(sourceMime, sourceFormat)) return null
    if (targets.audioChannels != null && !targets.copyAac) {
      val audioMime = metadata.tracks.first { it.sourceTrackId.valueOrNull?.toString() == targets.firstAudioTrackId }.mimeType.valueOrNull ?: return null
      if (!supportsDecode(audioMime, MediaFormat.createAudioFormat(audioMime, targets.audioSampleRate!!, targets.audioChannels))) return null
      val aac = MediaFormat.createAudioFormat("audio/mp4a-latm", targets.audioSampleRate, targets.audioChannels)
      aac.setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
      aac.setInteger(MediaFormat.KEY_AAC_PROFILE, CodecProfileLevel.AACObjectLC)
      if (infos.none { withinBudget() && it.isEncoder && it.supportedTypes.contains("audio/mp4a-latm") &&
          runCatching { it.getCapabilitiesForType("audio/mp4a-latm").isFormatSupported(aac) }.getOrDefault(false) }) return null
    }
    for (info in infos) {
      if (!withinBudget()) return null
      if (!info.isEncoder || !info.supportedTypes.contains(targets.videoMime)) continue
      val candidate = runCatching {
        val caps = info.getCapabilitiesForType(targets.videoMime)
        val level = caps.profileLevels.filter { it.profile == targets.platformProfile }.maxOfOrNull { it.level } ?: return@runCatching null
        val size = conversion.outputDimensions
        val format = MediaFormat.createVideoFormat(targets.videoMime, size.width, size.height)
        format.setFloat(MediaFormat.KEY_FRAME_RATE, conversion.outputFrameRate.toFloat())
        format.setInteger(MediaFormat.KEY_BIT_RATE, targets.bitrate)
        format.setInteger(MediaFormat.KEY_PROFILE, targets.platformProfile)
        format.setInteger(MediaFormat.KEY_LEVEL, level)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (caps.isFormatSupported(format)) SdrEncoderCapability(info.name, level) else null
      }.getOrNull()
      if (candidate != null && withinBudget()) return candidate
    }
    return null
  }
}
