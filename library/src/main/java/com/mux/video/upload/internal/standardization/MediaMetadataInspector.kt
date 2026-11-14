package com.mux.video.upload.internal.standardization

import android.media.AudioFormat as PlatformAudioFormat
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

internal enum class ContainerKind { IsoBaseMedia, Matroska, Other }
internal enum class TrackKind { Video, Audio, Other, Unknown }
internal enum class MetadataFailure { Unreadable, Malformed, LimitExceeded }

internal sealed interface MetadataInspectionResult {
  data class Success(val inspection: MediaMetadataInspection) : MetadataInspectionResult
  data class Failure(val reason: MetadataFailure) : MetadataInspectionResult
}

internal data class ColorMetadata(
  val standard: MediaFact<Int> = MediaFact.Unknown,
  val transfer: MediaFact<Int> = MediaFact.Unknown,
  val range: MediaFact<Int> = MediaFact.Unknown,
)

internal data class VideoTrackMetadata(
  val codec: MediaFact<VideoCodec>,
  val profile: MediaFact<VideoProfile>,
  val encodedDimensions: MediaFact<Dimensions>,
  val displayDimensions: MediaFact<Dimensions>,
  val rotationDegrees: MediaFact<Int>,
  val reportedPlatformRotationDegrees: MediaFact<Int>,
  val pixelFormat: MediaFact<PixelFormat>,
  val color: ColorMetadata,
  val dynamicRange: MediaFact<DynamicRange>,
)

internal data class AudioTrackMetadata(
  val format: MediaFact<AudioFormat>,
  val channelCount: MediaFact<Int>,
  val sampleRate: MediaFact<Int>,
  val encoderDelaySamples: MediaFact<Int>,
  val encoderPaddingSamples: MediaFact<Int>,
)

/** Reported metadata, not sample measurements or an effective presentation timeline. */
internal data class TrackMetadata(
  val extractorIndex: Int,
  /** Track order reported by MediaExtractor; ISO timing identity is checked separately. */
  val containerIndex: MediaFact<Int>,
  val sourceTrackId: MediaFact<Int>,
  val kind: TrackKind,
  val mimeType: MediaFact<String>,
  val reportedDurationSeconds: MediaFact<Double>,
  val reportedFrameRate: MediaFact<Double>,
  val reportedBitrate: MediaFact<Int>,
  val video: VideoTrackMetadata? = null,
  val audio: AudioTrackMetadata? = null,
)

/** Metadata alone does not prove duration or A/V offset; use StandardInputTimelineInspector. */
internal data class MediaMetadataInspection(
  val container: MediaFact<ContainerKind>,
  val tracks: List<TrackMetadata>,
  val facts: MediaFacts,
  val elapsedNanos: Long,
)

/** File-only metadata inspection. Owns its extractor; never exports or scans samples. */
internal class MediaMetadataInspector {
  fun inspect(file: File): MetadataInspectionResult {
    val started = System.nanoTime()
    if (!file.isFile || !file.canRead() || file.length() == 0L)
      return MetadataInspectionResult.Failure(MetadataFailure.Unreadable)
    val extractor = MediaExtractor()
    return try {
      val container = container(file)
      extractor.setDataSource(file.absolutePath)
      trackCountFailure(extractor.trackCount)?.let { return MetadataInspectionResult.Failure(it) }
      val tracks = (0 until extractor.trackCount).map { index ->
        MediaTrackMetadataReader.read(index, extractor.getTrackFormat(index))
      }
      val facts = MediaTrackMetadataReader.facts(tracks)
      MetadataInspectionResult.Success(MediaMetadataInspection(
        container, tracks, facts, System.nanoTime() - started))
    } catch (_: IOException) {
      MetadataInspectionResult.Failure(MetadataFailure.Unreadable)
    } catch (_: RuntimeException) {
      MetadataInspectionResult.Failure(MetadataFailure.Malformed)
    } finally {
      extractor.release()
    }
  }

  internal fun container(file: File): MediaFact<ContainerKind> {
    val header = ByteArray(12)
    val count = file.inputStream().use { it.read(header) }
    return when {
      count >= 8 && String(header, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "moov", "mdat", "free", "wide", "skip", "uuid", "pnot") ->
        MediaFact.Known(ContainerKind.IsoBaseMedia)
      count >= 4 && header.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) ->
        MediaFact.Known(ContainerKind.Matroska)
      count >= 8 -> MediaFact.Known(ContainerKind.Other)
      else -> MediaFact.Unknown
    }
  }

  companion object {
    private const val MAX_TRACKS = 64
    internal fun trackCountFailure(count: Int): MetadataFailure? = when {
      count <= 0 -> MetadataFailure.Malformed
      count > MAX_TRACKS -> MetadataFailure.LimitExceeded
      else -> null
    }
  }
}

internal object MediaTrackMetadataReader {
  fun read(index: Int, format: MediaFormat, apiLevel: Int = Build.VERSION.SDK_INT): TrackMetadata {
    val mime = format.string(MediaFormat.KEY_MIME)
    val kind = when {
      mime == null -> TrackKind.Unknown
      mime.startsWith("video/") -> TrackKind.Video
      mime.startsWith("audio/") -> TrackKind.Audio
      else -> TrackKind.Other
    }
    return TrackMetadata(
      index, MediaFact.Known(index), positiveCode(format.int("track-id")), kind, mime?.let { MediaFact.Known(it) } ?: MediaFact.Unknown,
      positive(format.long(MediaFormat.KEY_DURATION)?.toDouble()?.div(1_000_000)),
      positive(format.number(MediaFormat.KEY_FRAME_RATE)),
      positiveCode(format.int(MediaFormat.KEY_BIT_RATE)),
      video = if (kind == TrackKind.Video) video(format, mime, apiLevel) else null,
      audio = if (kind == TrackKind.Audio) audio(format, mime) else null,
    )
  }

  fun facts(tracks: List<TrackMetadata>): MediaFacts {
    val dolby = tracks.any { it.video?.dynamicRange == MediaFact.Known(DynamicRange.DolbyVision) }
    if (tracks.any { it.kind == TrackKind.Unknown }) return MediaFacts(
      dynamicRange = if (dolby) MediaFact.Known(DynamicRange.DolbyVision) else MediaFact.Unknown)
    val videos = tracks.filter { it.kind == TrackKind.Video }
    val audio = tracks.mapNotNull { it.audio }.map { AudioTrack(it.format) }
    // Dolby/base-layer views may alias one physical track; unproven identities cannot prove two tracks.
    val ids = videos.map { it.sourceTrackId.valueOrNull }
    val ambiguousViews = dolby && videos.size > 1 && (ids.any { it == null } || ids.distinct().size != ids.size)
    val base = MediaFacts(
      videoTrackCount = if (ambiguousViews) MediaFact.Unknown else MediaFact.Known(videos.size),
      audioTracks = MediaFact.Known(audio),
      dynamicRange = if (dolby) MediaFact.Known(DynamicRange.DolbyVision) else MediaFact.Unknown,
    )
    return videos.singleOrNull()?.video?.toFacts(base) ?: base
  }

  private fun video(format: MediaFormat, mime: String?, apiLevel: Int): VideoTrackMetadata {
    val codec = when (mime) {
      "video/avc" -> MediaFact.Known(VideoCodec.H264)
      "video/hevc" -> MediaFact.Known(VideoCodec.Hevc)
      null -> MediaFact.Unknown
      else -> MediaFact.Known(VideoCodec.Other)
    }
    val encoded = dimensions(format.int(MediaFormat.KEY_WIDTH), format.int(MediaFormat.KEY_HEIGHT))
    val platformRotation = format.int(MediaFormat.KEY_ROTATION)?.let {
      if (it % 90 == 0) MediaFact.Known((it % 360 + 360) % 360) else MediaFact.Unknown
    } ?: MediaFact.Unknown
    // Match the shared transform convention while retaining Android's raw angle as an observation.
    val rotation = platformRotation.valueOrNull?.let { MediaFact.Known((360 - it) % 360) }
      ?: if (!format.containsKey(MediaFormat.KEY_ROTATION)) MediaFact.Known(0) else MediaFact.Unknown
    val platformColor = if (apiLevel >= 24) ColorMetadata(
      positiveCode(format.int(MediaFormat.KEY_COLOR_STANDARD)),
      positiveCode(format.int(MediaFormat.KEY_COLOR_TRANSFER)),
      positiveCode(format.int(MediaFormat.KEY_COLOR_RANGE)),
    ) else ColorMetadata()
    val profile = platformProfile(codec.valueOrNull, if (apiLevel >= 24) format.int(MediaFormat.KEY_PROFILE) else null)
    val platformPixels = pixels(format.int(MediaFormat.KEY_COLOR_FORMAT), apiLevel)
    val fallback = if (platformPixels == MediaFact.Unknown || platformColor.transfer == MediaFact.Unknown || profile == MediaFact.Unknown)
      CodecMetadataReader.video(codec.valueOrNull, (0..2).mapNotNull { format.bytes("csd-$it") }) else CodecMetadata()
    val color = ColorMetadata(
      platformColor.standard.takeUnless { it == MediaFact.Unknown } ?: fallback.color.standard,
      platformColor.transfer.takeUnless { it == MediaFact.Unknown } ?: fallback.color.transfer,
      platformColor.range.takeUnless { it == MediaFact.Unknown } ?: fallback.color.range,
    )
    val range = if (mime == "video/dolby-vision") MediaFact.Known(DynamicRange.DolbyVision)
      else dynamicRange(color.transfer)
    return VideoTrackMetadata(codec,
      profile.takeUnless { it == MediaFact.Unknown } ?: fallback.profile,
      encoded, display(format, encoded, rotation), rotation, platformRotation,
      platformPixels.takeUnless { it == MediaFact.Unknown } ?: fallback.pixelFormat, color, range)
  }

  private fun audio(format: MediaFormat, mime: String?): AudioTrackMetadata {
    val count = positiveCode(format.int(MediaFormat.KEY_CHANNEL_COUNT))
    val audio = when (mime) {
      "audio/mp4a-latm" -> {
        val mask = format.int(MediaFormat.KEY_CHANNEL_MASK)?.takeIf { it != 0 }
        val expected = when (count.valueOrNull) {
          1 -> setOf(PlatformAudioFormat.CHANNEL_OUT_MONO)
          2 -> setOf(PlatformAudioFormat.CHANNEL_OUT_STEREO)
          6 -> setOf(PlatformAudioFormat.CHANNEL_OUT_5POINT1)
          else -> emptySet()
        }
        val layout = if ((mask != null && mask !in expected) || (count.valueOrNull == 6 && mask == null))
          MediaFact.Unknown else aacChannelLayout(count.valueOrNull)
        layout.valueOrNull?.let { MediaFact.Known(AudioFormat.Aac(it)) } ?: MediaFact.Unknown
      }
      null -> MediaFact.Unknown
      else -> MediaFact.Known(AudioFormat.OtherCodec)
    }
    return AudioTrackMetadata(audio, count, positiveCode(format.int(MediaFormat.KEY_SAMPLE_RATE)),
      nonNegative(format.int(MediaFormat.KEY_ENCODER_DELAY)), nonNegative(format.int(MediaFormat.KEY_ENCODER_PADDING)))
  }

  private fun display(format: MediaFormat, encoded: MediaFact<Dimensions>, rotation: MediaFact<Int>): MediaFact<Dimensions> {
    val size = encoded.valueOrNull ?: return MediaFact.Unknown
    val degrees = rotation.valueOrNull ?: return MediaFact.Unknown
    val crop = listOf("crop-left", "crop-right", "crop-top", "crop-bottom").map { format.int(it) }
    val visible = if (crop.all { it == null }) size else {
      if (crop.any { it == null }) return MediaFact.Unknown
      val (left, right, top, bottom) = crop.map { it!! }
      if (left < 0 || top < 0 || right < left || bottom < top || right >= size.width || bottom >= size.height)
        return MediaFact.Unknown
      Dimensions(right - left + 1, bottom - top + 1)
    }
    val sarWidth = format.int("sar-width")
    val sarHeight = format.int("sar-height")
    if ((sarWidth != null || sarHeight != null) &&
      (sarWidth == null || sarHeight == null || sarWidth <= 0 || sarWidth != sarHeight)) return MediaFact.Unknown
    return MediaFact.Known(if (degrees == 90 || degrees == 270) Dimensions(visible.height, visible.width) else visible)
  }

  private fun pixels(color: Int?, apiLevel: Int): MediaFact<PixelFormat> = when (color) {
    CodecCapabilities.COLOR_FormatYUV420Planar, CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
    CodecCapabilities.COLOR_FormatYUV420SemiPlanar, CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
    CodecCapabilities.COLOR_FormatYUV420Flexible -> MediaFact.Known(PixelFormat(8, ChromaSubsampling.Yuv420))
    CodecCapabilities.COLOR_FormatYUVP010 -> if (apiLevel >= 33)
      MediaFact.Known(PixelFormat(10, ChromaSubsampling.Yuv420)) else MediaFact.Unknown
    else -> MediaFact.Unknown
  }

  private fun dimensions(width: Int?, height: Int?): MediaFact<Dimensions> =
    if (width != null && height != null && width > 0 && height > 0) MediaFact.Known(Dimensions(width, height))
    else MediaFact.Unknown

  private fun platformProfile(codec: VideoCodec?, profile: Int?): MediaFact<VideoProfile> =
    if (profile == null) MediaFact.Unknown else MediaFact.Known(when (codec) {
      VideoCodec.H264 -> when (profile) {
        CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileConstrainedBaseline -> VideoProfile.H264Baseline
        CodecProfileLevel.AVCProfileMain -> VideoProfile.H264Main
        CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh -> VideoProfile.H264High
        else -> VideoProfile.Other
      }
      VideoCodec.Hevc -> when (profile) {
        CodecProfileLevel.HEVCProfileMain -> VideoProfile.HevcMain
        CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
        CodecProfileLevel.HEVCProfileMain10HDR10Plus -> VideoProfile.HevcMain10
        else -> VideoProfile.Other
      }
      else -> VideoProfile.Other
    })

  private fun dynamicRange(transfer: MediaFact<Int>): MediaFact<DynamicRange> = when (transfer.valueOrNull) {
    MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_TRANSFER_LINEAR -> MediaFact.Known(DynamicRange.Sdr)
    MediaFormat.COLOR_TRANSFER_HLG -> MediaFact.Known(DynamicRange.Hlg)
    MediaFormat.COLOR_TRANSFER_ST2084 -> MediaFact.Known(DynamicRange.Pq)
    else -> MediaFact.Unknown
  }
}

private fun MediaFormat.int(key: String): Int? = try { if (containsKey(key)) getInteger(key) else null } catch (_: RuntimeException) { null }
private fun MediaFormat.long(key: String): Long? = try { if (containsKey(key)) getLong(key) else null } catch (_: RuntimeException) { null }
private fun MediaFormat.string(key: String): String? = try { if (containsKey(key)) getString(key) else null } catch (_: RuntimeException) { null }
private fun MediaFormat.number(key: String): Double? = try { if (containsKey(key)) getFloat(key).toDouble() else null }
  catch (_: RuntimeException) { int(key)?.toDouble() }
private fun MediaFormat.bytes(key: String): ByteArray? = try {
  val buffer: ByteBuffer? = getByteBuffer(key)?.duplicate()
  if (buffer == null || buffer.remaining() !in 1..CodecMetadataReader.MAX_CONFIGURATION_BYTES) null
  else ByteArray(buffer.remaining()).also { buffer.get(it) }
} catch (_: RuntimeException) { null }

private fun nonNegative(value: Int?): MediaFact<Int> =
  if (value != null && value >= 0) MediaFact.Known(value) else MediaFact.Unknown

private fun VideoTrackMetadata.toFacts(base: MediaFacts): MediaFacts = base.copy(
  videoCodec = codec, videoProfile = profile, encodedDimensions = encodedDimensions,
  displayDimensions = displayDimensions, rotationDegrees = rotationDegrees,
  pixelFormat = pixelFormat, dynamicRange = dynamicRange,
)

internal fun positive(value: Double?): MediaFact<Double> =
  if (value != null && value.isFinite() && value > 0) MediaFact.Known(value) else MediaFact.Unknown

internal fun positiveCode(value: Int?): MediaFact<Int> =
  if (value != null && value > 0) MediaFact.Known(value) else MediaFact.Unknown

internal fun aacChannelLayout(count: Int?): MediaFact<AudioChannelLayout> =
  if (count == null || count <= 0) MediaFact.Unknown else MediaFact.Known(when (count) {
    1 -> AudioChannelLayout.Mono
    2 -> AudioChannelLayout.Stereo
    6 -> AudioChannelLayout.FivePointOne
    else -> AudioChannelLayout.Other
  })
