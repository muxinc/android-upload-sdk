package com.mux.video.upload.internal.standardization

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.os.Build
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

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
  val sourceFrameRate: Double? = null,
) {
  fun estimatedOutputBytes(duration: Double): Long? {
    if (!duration.isFinite() || duration <= 0) return null
    val bytes = ceil(duration * (bitrate + if (audioChannels != null) 384_000 else 0) / 8 * 1.5) + 8 * 1024 * 1024
    return bytes.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
  }

  companion object {
    const val AAC_BITRATE = 160_000
    const val AAC_PROFILE = CodecProfileLevel.AACObjectLC

    fun from(conversion: StandardInputConversion, metadata: MediaMetadataInspection,
      source: MediaSampleInspection): SdrEncodingTargets? {
      val facts = source.facts
      // LINEAR is SDR, but this surface path has no proof of its transfer handling.
      if (metadata.tracks.any { it.video?.let { video ->
          video.color.transfer.valueOrNull == MediaFormat.COLOR_TRANSFER_LINEAR
        } == true }) return null
      if (conversion.toneMapsToSdr) {
        val video = metadata.tracks.singleOrNull { it.kind == TrackKind.Video }?.video ?: return null
        val transfer = when (conversion.sourceDynamicRange) {
          DynamicRange.Hlg -> MediaFormat.COLOR_TRANSFER_HLG
          DynamicRange.Pq -> MediaFormat.COLOR_TRANSFER_ST2084
          else -> return null
        }
        val pixels = facts.pixelFormat.valueOrNull ?: return null
        val profiles = when (conversion.sourceCodec) {
          VideoCodec.H264 -> setOf(VideoProfile.H264Baseline, VideoProfile.H264Main, VideoProfile.H264High)
          VideoCodec.Hevc -> setOf(if (pixels.bitDepth == 10) VideoProfile.HevcMain10 else VideoProfile.HevcMain)
          else -> return null
        }
        if (video.codec != MediaFact.Known(conversion.sourceCodec) || video.profile.valueOrNull !in profiles ||
          video.dynamicRange != MediaFact.Known(conversion.sourceDynamicRange) ||
          video.pixelFormat != facts.pixelFormat || pixels.chromaSubsampling != ChromaSubsampling.Yuv420 ||
          pixels.bitDepth !in (if (conversion.sourceCodec == VideoCodec.Hevc) setOf(8, 10) else setOf(8)) ||
          video.color.standard.valueOrNull != MediaFormat.COLOR_STANDARD_BT2020 ||
          video.color.transfer.valueOrNull != transfer ||
          video.color.range.valueOrNull !in setOf(MediaFormat.COLOR_RANGE_FULL, MediaFormat.COLOR_RANGE_LIMITED)) return null
      } else if (conversion.sourceDynamicRange != DynamicRange.Sdr) return null
      if (source.status != SampleScanStatus.Complete ||
        facts.dynamicRange != MediaFact.Known(conversion.sourceDynamicRange) ||
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
        !size.fitsWithin(conversion.selection.standardizedOutputDimensions) ||
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
      // Direct adapter callers must also preserve Mux's audio choice for multi-track inputs.
      if (audioFacts.size > 1 || audio.size > 1) return null
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
      var gopSeconds = floor(interval * 0.9).toInt()
      if (conversion.outputCadence == OutputCadence.PreserveSourceTimestamps &&
        facts.cadence == MediaFact.Known(Cadence.Variable)) {
        val times = source.timeline.videoPresentationSeconds.valueOrNull!!
        val duration = source.timeline.videoDurationSeconds.valueOrNull!!
        if (times.size < 2 || times.any { !it.isFinite() } || !duration.isFinite() || duration <= 0) return null
        val end = times.first() + duration
        if (!end.isFinite() || end < times.last()) return null
        for (index in 1 until times.size) {
          if (times[index] <= times[index - 1]) return null
        }
        // Encoders commonly turn seconds into nominal-rate frames. Check every real
        // frame window, including the final sample's duration, without retiming gaps.
        while (gopSeconds >= 1) {
          val frames = ceil(gopSeconds * rate).toInt()
          val fits = times.indices.all { index ->
            val windowEnd = times.getOrNull(index + frames) ?: end
            windowEnd - times[index] <= interval * 0.9 + 2e-6
          }
          if (fits) break
          gopSeconds--
        }
        if (gopSeconds < 1) return null
      }
      return SdrEncodingTargets(
        if (expectedCodec == VideoCodec.Hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264,
        // H.264 prefers High like iOS; preflight may select Baseline when High is unavailable.
        if (expectedCodec == VideoCodec.Hevc) VideoProfile.HevcMain else VideoProfile.H264High,
        if (expectedCodec == VideoCodec.Hevc) CodecProfileLevel.HEVCProfileMain else CodecProfileLevel.AVCProfileHigh,
        minOf(6_000_000L.takeIf { size.longSide <= 2048 } ?: 15_000_000L,
          conversion.outputPolicyLimits.maximumAverageBitrate * 3 / 4).toInt().takeIf { it > 0 } ?: return null,
        maxOf(1, gopSeconds), channels, sampleRate, copy, first?.sourceTrackId?.valueOrNull?.toString(), sourceRate,
      )
    }
  }
}

/** Media3 encodes in landscape and applies output rotation separately from the encoder format. */
internal val StandardInputConversion.encoderDimensions: Dimensions
  get() = Dimensions(maxOf(outputDimensions.width, outputDimensions.height), minOf(outputDimensions.width, outputDimensions.height))

/**
 * The encoder chosen by preflight. [profile]/[platformProfile] override the targets' preferred
 * profile when the encoder only supports a fallback profile (for example, H.264 Baseline).
 */
internal data class SdrEncoderCapability(
  val name: String,
  val level: Int,
  val profile: VideoProfile? = null,
  val platformProfile: Int? = null,
)

/** The encoder settings actually used for export and output validation. */
internal fun SdrEncodingTargets.withEncoder(capability: SdrEncoderCapability): SdrEncodingTargets =
  if (capability.profile == null || capability.platformProfile == null) this
  else copy(profile = capability.profile, platformProfile = capability.platformProfile)

/** Each part of the device/source conversion path, reported separately for planning and diagnostics. */
internal data class SdrPreflight(
  /** Null when an earlier gate (cancellation, tone-mapping support) made the check pointless. */
  val sourceDecodable: Boolean?,
  val audioPreparable: Boolean?,
  val toneMapSupported: Boolean,
  val encoder: SdrEncoderCapability?,
) {
  val supported: Boolean get() = sourceDecodable == true && audioPreparable == true && toneMapSupported && encoder != null
}

/** Bounded advertised-format guard. Export and output reinspection remain authoritative. */
internal class SdrCapabilityPreflight(
  private val codecInfos: () -> Array<MediaCodecInfo> = { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos },
  private val nanoTime: () -> Long = System::nanoTime,
  private val sdk: Int = Build.VERSION.SDK_INT,
  private val supportsHdrOpenGl: () -> Boolean = ::supportsHdrOpenGl,
) {
  fun videoEncoder(conversion: StandardInputConversion, metadata: MediaMetadataInspection,
    targets: SdrEncodingTargets, isCancelled: () -> Boolean): SdrEncoderCapability? =
    inspect(conversion, metadata, targets, isCancelled).takeIf { it.supported }?.encoder

  fun inspect(conversion: StandardInputConversion, metadata: MediaMetadataInspection,
    targets: SdrEncodingTargets, isCancelled: () -> Boolean): SdrPreflight {
    val unsupported = SdrPreflight(null, null, false, null)
    if (isCancelled()) return unsupported
    // Tone mapping needs API 29+ and the HDR GL path; without them no codec query can help.
    if (conversion.toneMapsToSdr && (sdk < 29 || !supportsHdrOpenGl() || isCancelled())) return unsupported
    val toneMap = true
    val infos = codecInfos()
    // Native enumeration cannot be interrupted; budget subsequent capability queries.
    if (isCancelled()) return unsupported
    val started = nanoTime()
    var queries = 0
    fun withinBudget() = !isCancelled() && nanoTime() - started < 500_000_000L
    fun supports(info: MediaCodecInfo, mime: String, format: MediaFormat): Boolean {
      if (!withinBudget() || queries >= 128) return false
      queries++
      return runCatching { info.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
    }
    fun supportsDecode(mime: String, format: MediaFormat): Boolean {
      for (info in infos) {
        if (!withinBudget()) return false
        if (!info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } && supports(info, mime, format)) return true
      }
      return false
    }
    val video = metadata.tracks.singleOrNull { it.kind == TrackKind.Video } ?: return unsupported
    val sourceMime = video.mimeType.valueOrNull ?: return unsupported
    val sourceSize = video.video?.encodedDimensions?.valueOrNull ?: return unsupported
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
    val decodable = if (conversion.toneMapsToSdr && (sourceProfile == null || targets.sourceFrameRate == null)) false else {
      // isFormatSupported checks profile/size/rate, not HDR color handling. Export must still succeed.
      if (conversion.toneMapsToSdr) sourceFormat.setFloat(MediaFormat.KEY_FRAME_RATE, targets.sourceFrameRate!!.toFloat())
      supportsDecode(sourceMime, sourceFormat)
    }
    val audio = if (targets.audioChannels == null || targets.copyAac) true else {
      val audioMime = metadata.tracks.firstOrNull { it.sourceTrackId.valueOrNull?.toString() == targets.firstAudioTrackId }
        ?.mimeType?.valueOrNull
      audioMime != null &&
        supportsDecode(audioMime, MediaFormat.createAudioFormat(audioMime, targets.audioSampleRate!!, targets.audioChannels)) && run {
          val aac = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, targets.audioSampleRate, targets.audioChannels)
          aac.setInteger(MediaFormat.KEY_BIT_RATE, SdrEncodingTargets.AAC_BITRATE)
          aac.setInteger(MediaFormat.KEY_AAC_PROFILE, SdrEncodingTargets.AAC_PROFILE)
          infos.any { withinBudget() && it.isEncoder && it.supportedTypes.contains(MimeTypes.AUDIO_AAC) &&
            supports(it, MimeTypes.AUDIO_AAC, aac) }
        }
    }
    return SdrPreflight(decodable, audio, toneMap, findEncoder(conversion, targets, infos, ::withinBudget) {
      if (queries >= 128) false else { queries++; true }
    })
  }

  /** Prefer the targets' profile, then any listed fallback, across all encoders in platform order. */
  private fun findEncoder(conversion: StandardInputConversion, targets: SdrEncodingTargets,
    infos: Array<MediaCodecInfo>, withinBudget: () -> Boolean, takeQuery: () -> Boolean): SdrEncoderCapability? {
    for ((profile, platformProfile) in targets.encoderProfiles()) {
      val candidateTargets = targets.copy(profile = profile, platformProfile = platformProfile)
      for (info in infos) {
        if (!withinBudget()) return null
        if (!info.isEncoder || !info.supportedTypes.contains(targets.videoMime)) continue
        if (!takeQuery()) return null
        val candidate = runCatching {
          val caps = info.getCapabilitiesForType(targets.videoMime)
          val level = caps.profileLevels.filter { it.profile == platformProfile }.maxOfOrNull { it.level } ?: return@runCatching null
          val size = conversion.encoderDimensions
          if (caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, conversion.outputFrameRate) != true)
            return@runCatching null
          val requested = Format.Builder().setSampleMimeType(targets.videoMime).setWidth(size.width).setHeight(size.height)
            .setFrameRate(conversion.outputFrameRate.toFloat()).setAverageBitrate(targets.bitrate)
            .apply { if (conversion.toneMapsToSdr) setColorInfo(ColorInfo.SDR_BT709_LIMITED) }.build()
          val format = SdrVideoEncoderConfiguration.create(requested, candidateTargets, level)
          if (caps.isFormatSupported(format)) SdrEncoderCapability(info.name, level, profile, platformProfile) else null
        }.getOrNull()
        if (candidate != null && withinBudget()) return candidate
      }
    }
    return null
  }
}

/** H.264 prefers High (matching iOS) and accepts Baseline only when no encoder offers High. */
internal fun SdrEncodingTargets.encoderProfiles(): List<Pair<VideoProfile, Int>> =
  if (videoMime == MimeTypes.VIDEO_H264 && profile == VideoProfile.H264High)
    listOf(VideoProfile.H264High to CodecProfileLevel.AVCProfileHigh,
      VideoProfile.H264Baseline to CodecProfileLevel.AVCProfileBaseline)
  else listOf(profile to platformProfile)

/**
 * Source and device proof for one concrete conversion, supplied to the planner before it may
 * choose conversion. Unproven parts become explicit missing capabilities and select the original.
 */
internal class SdrPlanningCapabilityProbe(
  private val preflight: (StandardInputConversion, MediaMetadataInspection, SdrEncodingTargets, () -> Boolean) -> SdrPreflight =
    SdrCapabilityPreflight()::inspect,
) {
  fun capabilities(conversion: StandardInputConversion, metadata: MediaMetadataInspection,
    source: MediaSampleInspection, isCancelled: () -> Boolean): PlanningCapabilities {
    if (isCancelled()) return PlanningCapabilities()
    // Targets prove the complete source timeline, cadence handling, and single-track audio selection.
    // Export sizes its output from the effective duration; without it no conversion can be proven.
    val duration = source.timeline.durationSeconds.valueOrNull
    val targets = SdrEncodingTargets.from(conversion, metadata, source)
      ?.takeIf { duration != null && duration.isFinite() && duration > 0 }
    if (targets == null || isCancelled()) {
      StandardInputDiagnostics.capabilities(targetsAvailable = false, preflight = null)
      return PlanningCapabilities()
    }
    val result = preflight(conversion, metadata, targets, isCancelled)
    StandardInputDiagnostics.capabilities(targetsAvailable = true, preflight = result)
    val encodes = result.encoder != null
    return PlanningCapabilities(
      sourceIsDecodable = result.sourceDecodable == true,
      sourceTimelineIsProven = true,
      encodableVideoCodecs = if (encodes) setOf(conversion.outputCodec) else emptySet(),
      // Targets prove the transformations this adapter implements. Decode/encode, audio and
      // tone mapping have separate gates; actual output must still pass reinspection.
      remediableRequirements = setOf(PolicyRequirement.VideoCodec, PolicyRequirement.VideoResolution,
        PolicyRequirement.FrameRate, PolicyRequirement.AverageBitrate, PolicyRequirement.MaximumGopBitrate,
        PolicyRequirement.KeyframeInterval, PolicyRequirement.GopStructure, PolicyRequirement.PixelFormat,
        PolicyRequirement.EditList),
      toneMappableDynamicRanges = if (conversion.toneMapsToSdr && result.toneMapSupported)
        setOf(conversion.sourceDynamicRange) else emptySet(),
      canPrepareCompliantAacAudio = result.audioPreparable == true,
    )
  }
}

/** Probe the same ES3 YUV sampling path used by Media3; never disturb another current context. */
private fun supportsHdrOpenGl(): Boolean = runCatching {
  if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) return@runCatching false
  val display = GlUtil.getDefaultEglDisplay()
  val context = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
  try {
    val surface = GlUtil.createFocusedPlaceholderEglSurface(context, display)
    try { GlUtil.isYuvTargetExtensionSupported() }
    finally { GlUtil.destroyEglSurface(display, surface) }
  } finally { GlUtil.destroyEglContext(display, context) }
}.getOrDefault(false)
