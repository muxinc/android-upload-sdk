package com.mux.video.upload.internal.standardization

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal enum class ContainerKind { IsoBaseMedia, Matroska, Other }
internal enum class TrackKind { Video, Audio, Other, Unknown }
internal enum class MetadataFailure { Unreadable, Malformed, LimitExceeded }

internal sealed interface MetadataInspectionResult {
  data class Success(val inspection: MediaMetadataInspection) : MetadataInspectionResult
  data class Failure(val reason: MetadataFailure) : MetadataInspectionResult
}

internal data class VideoTrackMetadata(
  val codec: MediaFact<VideoCodec>,
  val profile: MediaFact<VideoProfile>,
  val encodedDimensions: MediaFact<Dimensions>,
  val displayDimensions: MediaFact<Dimensions>,
  val rotationDegrees: MediaFact<Int>,
  val reportedPlatformRotationDegrees: MediaFact<Int>,
  val configuration: CodecMetadata,
  val platformColor: ColorMetadata,
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

internal data class MediaMetadataInspection(
  val container: MediaFact<ContainerKind>,
  val tracks: List<TrackMetadata>,
  val isoTracks: MediaFact<List<IsoTrackMetadata>>,
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
      if (extractor.trackCount !in 1..MAX_TRACKS)
        return MetadataInspectionResult.Failure(MetadataFailure.LimitExceeded)
      val iso = if (container == MediaFact.Known(ContainerKind.IsoBaseMedia))
        IsoContainerMetadataReader.read(file) else MediaFact.Unknown
      val tracks = (0 until extractor.trackCount).map { index ->
        val format = extractor.getTrackFormat(index)
        val orientation = if (format.string(MediaFormat.KEY_MIME)?.startsWith("video/") == true)
          iso.valueOrNull?.filter { it.handler == "vide" }?.singleOrNull()?.rotationDegrees else null
        val track = MediaTrackMetadataReader.read(index, format, containerRotation = orientation)
        val containers = iso.valueOrNull
        val id = track.sourceTrackId.valueOrNull
        val match = if (id != null) containers?.indexOfFirst { it.trackId.valueOrNull == id } else null
        val ordered = containers?.getOrNull(index)?.handler
        val sameKind = (ordered == "vide" && track.kind == TrackKind.Video) ||
          (ordered == "soun" && track.kind == TrackKind.Audio)
        val containerIndex = when {
          match != null && match >= 0 &&
            ((containers!![match].handler == "vide" && track.kind == TrackKind.Video) ||
              (containers[match].handler == "soun" && track.kind == TrackKind.Audio)) -> MediaFact.Known(match)
          id == null && containers?.size == extractor.trackCount && sameKind -> MediaFact.Known(index)
          else -> MediaFact.Unknown
        }
        track.copy(containerIndex = containerIndex)
      }
      val containerFacts = MediaContainerMetadataReader.facts(container, iso, tracks)
      MetadataInspectionResult.Success(MediaMetadataInspection(
        container, tracks, iso, containerFacts, System.nanoTime() - started))
    } catch (_: IOException) {
      MetadataInspectionResult.Failure(MetadataFailure.Unreadable)
    } catch (_: RuntimeException) {
      MetadataInspectionResult.Failure(MetadataFailure.Malformed)
    } finally {
      extractor.release()
    }
  }

  private fun container(file: File): MediaFact<ContainerKind> {
    val header = ByteArray(12)
    val count = file.inputStream().use { it.read(header) }
    return when {
      count >= 8 && String(header, 4, 4, Charsets.US_ASCII) in listOf("ftyp", "moov", "mdat", "free", "wide") ->
        MediaFact.Known(ContainerKind.IsoBaseMedia)
      count >= 4 && header.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) ->
        MediaFact.Known(ContainerKind.Matroska)
      count >= 8 -> MediaFact.Known(ContainerKind.Other)
      else -> MediaFact.Unknown
    }
  }

  companion object { private const val MAX_TRACKS = 64 }
}

/** Reconcile physical container tracks with extractor views, including Dolby Vision base-layer views. */
internal object MediaContainerMetadataReader {
  fun facts(container: MediaFact<ContainerKind>, iso: MediaFact<List<IsoTrackMetadata>>,
    tracks: List<TrackMetadata>): MediaFacts {
    val dolby = iso.valueOrNull?.any { it.hasDolbyVisionConfiguration } == true ||
      tracks.any { it.video?.dynamicRange == MediaFact.Known(DynamicRange.DolbyVision) }
    if (container != MediaFact.Known(ContainerKind.IsoBaseMedia)) return MediaTrackMetadataReader.facts(tracks)
    val containers = iso.valueOrNull
      ?: return MediaFacts(dynamicRange = if (dolby) MediaFact.Known(DynamicRange.DolbyVision) else MediaFact.Unknown)
    if (containers.any { it.handler == null }) return MediaFacts()
    val videoIndices = containers.indices.filter { containers[it].handler == "vide" }
    val audioIndices = containers.indices.filter { containers[it].handler == "soun" }
    val audioViews = tracks.filter { it.kind == TrackKind.Audio }.groupBy { it.containerIndex.valueOrNull }
    val orderedAudio = if (audioViews.keys == audioIndices.toSet() && audioViews.values.all { it.size == 1 }) {
      MediaFact.Known(audioIndices.map { index ->
        val format = if (containers[index].sampleEntries.size == 1) audioViews[index]!!.single().audio!!.format
          else MediaFact.Unknown
        AudioTrack(format)
      })
    } else MediaFact.Unknown
    val base = MediaFacts(videoTrackCount = MediaFact.Known(videoIndices.size), audioTracks = orderedAudio,
      dynamicRange = if (dolby) MediaFact.Known(DynamicRange.DolbyVision) else MediaFact.Unknown)
    val index = videoIndices.singleOrNull() ?: return base
    val description = containers[index]
    if (description.sampleEntries.size != 1) return base
    val views = tracks.filter { it.kind == TrackKind.Video }
    if (views.any { it.containerIndex.valueOrNull != index }) return base
    val selected = if (views.size == 1) views.single() else {
      if (!dolby || views.size != 2 || views.count { it.mimeType.valueOrNull == "video/dolby-vision" } != 1) return base
      val codec = when (description.sampleEntries.single()) {
        "hvc1", "hev1", "dvh1", "dvhe" -> VideoCodec.Hevc
        "avc1", "avc3", "dva1", "dvav" -> VideoCodec.H264
        else -> return base
      }
      views.singleOrNull { it.video?.codec == MediaFact.Known(codec) } ?: return base
    }
    val video = selected.video ?: return base
    return base.copy(
      videoCodec = video.codec, videoProfile = video.profile, encodedDimensions = video.encodedDimensions,
      displayDimensions = if (description.hasSimpleOrientation && description.hasSimpleSampleGeometry)
        video.displayDimensions else MediaFact.Unknown,
      rotationDegrees = if (description.hasSimpleOrientation) video.rotationDegrees else MediaFact.Unknown,
      pixelFormat = video.configuration.pixelFormat,
      dynamicRange = if (dolby) MediaFact.Known(DynamicRange.DolbyVision) else video.dynamicRange,
    )
  }
}

internal object MediaTrackMetadataReader {
  fun read(index: Int, format: MediaFormat, apiLevel: Int = Build.VERSION.SDK_INT,
    containerRotation: MediaFact<Int>? = null): TrackMetadata {
    val mime = format.string(MediaFormat.KEY_MIME)
    val kind = when {
      mime == null -> TrackKind.Unknown
      mime.startsWith("video/") -> TrackKind.Video
      mime.startsWith("audio/") -> TrackKind.Audio
      else -> TrackKind.Other
    }
    return TrackMetadata(
      index, MediaFact.Unknown, positiveCode(format.int("track-id")), kind, mime?.let { MediaFact.Known(it) } ?: MediaFact.Unknown,
      positive(format.long(MediaFormat.KEY_DURATION)?.toDouble()?.div(1_000_000)),
      positive(format.number(MediaFormat.KEY_FRAME_RATE)),
      positiveCode(format.int(MediaFormat.KEY_BIT_RATE)),
      video = if (kind == TrackKind.Video) video(format, mime, apiLevel, containerRotation) else null,
      audio = if (kind == TrackKind.Audio) audio(format, mime) else null,
    )
  }

  fun facts(tracks: List<TrackMetadata>): MediaFacts {
    if (tracks.any { it.kind == TrackKind.Unknown }) return MediaFacts()
    val videos = tracks.filter { it.kind == TrackKind.Video }
    val audio = tracks.mapNotNull { it.audio }.map { AudioTrack(it.format) }
    val base = MediaFacts(videoTrackCount = MediaFact.Known(videos.size), audioTracks = MediaFact.Known(audio))
    val video = videos.singleOrNull()?.video ?: return base
    return base.copy(
      videoCodec = video.codec, videoProfile = video.profile,
      encodedDimensions = video.encodedDimensions, displayDimensions = video.displayDimensions,
      rotationDegrees = video.rotationDegrees, pixelFormat = video.configuration.pixelFormat,
      dynamicRange = video.dynamicRange,
      // Duration, nominal rate and declared bitrate require later sample/timeline proof.
    )
  }

  private fun video(format: MediaFormat, mime: String?, apiLevel: Int,
    containerRotation: MediaFact<Int>?): VideoTrackMetadata {
    val codec = when (mime) {
      "video/avc" -> MediaFact.Known(VideoCodec.H264)
      "video/hevc" -> MediaFact.Known(VideoCodec.Hevc)
      null -> MediaFact.Unknown
      else -> MediaFact.Known(VideoCodec.Other)
    }
    val csd = (0..2).mapNotNull { format.bytes("csd-$it") }
    val config = CodecMetadataReader.video(codec.valueOrNull, csd)
    val encoded = dimensions(format.int(MediaFormat.KEY_WIDTH), format.int(MediaFormat.KEY_HEIGHT))
    val platformRotation = format.int(MediaFormat.KEY_ROTATION)?.let {
      if (it % 90 == 0) MediaFact.Known((it % 360 + 360) % 360) else MediaFact.Unknown
    } ?: MediaFact.Unknown
    val rotation = containerRotation ?: platformRotation
    val platformColor = if (apiLevel >= 24) ColorMetadata(
      positiveCode(format.int(MediaFormat.KEY_COLOR_STANDARD)),
      positiveCode(format.int(MediaFormat.KEY_COLOR_TRANSFER)),
      positiveCode(format.int(MediaFormat.KEY_COLOR_RANGE)),
    ) else ColorMetadata()
    val transfer = reconcile(platformColor.transfer, config.color.transfer)
    val range = if (mime == "video/dolby-vision") MediaFact.Known(DynamicRange.DolbyVision)
      else dynamicRange(transfer)
    return VideoTrackMetadata(codec,
      reconcile(platformProfile(codec.valueOrNull, format.int(MediaFormat.KEY_PROFILE)), config.profile),
      encoded, display(format, encoded, rotation, config.pixelAspectRatio), rotation, platformRotation,
      config, platformColor, range)
  }

  private fun audio(format: MediaFormat, mime: String?): AudioTrackMetadata {
    val count = positiveCode(format.int(MediaFormat.KEY_CHANNEL_COUNT))
    val audio = when (mime) {
      "audio/mp4a-latm" -> CodecMetadataReader.aacLayout(format.bytes("csd-0"), count.valueOrNull)
        .valueOrNull?.let { MediaFact.Known(AudioFormat.Aac(it)) } ?: MediaFact.Unknown
      null -> MediaFact.Unknown
      else -> MediaFact.Known(AudioFormat.OtherCodec)
    }
    return AudioTrackMetadata(audio, count, positiveCode(format.int(MediaFormat.KEY_SAMPLE_RATE)),
      nonNegative(format.int(MediaFormat.KEY_ENCODER_DELAY)), nonNegative(format.int(MediaFormat.KEY_ENCODER_PADDING)))
  }

  private fun display(format: MediaFormat, encoded: MediaFact<Dimensions>, rotation: MediaFact<Int>,
    headerAspect: MediaFact<Double>): MediaFact<Dimensions> {
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
    val platformAspect = if (sarWidth != null && sarHeight != null && sarWidth > 0 && sarHeight > 0)
      MediaFact.Known(sarWidth.toDouble() / sarHeight) else MediaFact.Unknown
    if ((sarWidth != null || sarHeight != null) && platformAspect == MediaFact.Unknown) return MediaFact.Unknown
    val aspect = reconcile(platformAspect, headerAspect).valueOrNull ?: return MediaFact.Unknown
    val width = visible.width * aspect
    if (!width.isFinite() || width < 1 || width > Int.MAX_VALUE) return MediaFact.Unknown
    val display = Dimensions(width.roundToInt(), visible.height)
    return MediaFact.Known(if (degrees == 90 || degrees == 270) Dimensions(display.height, display.width) else display)
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
private fun MediaFormat.bytes(key: String): ByteArray? {
  return try {
    val buffer: ByteBuffer = getByteBuffer(key)?.duplicate() ?: return null
    if (buffer.remaining() !in 1..CodecMetadataReader.MAX_CONFIGURATION_BYTES) null
    else ByteArray(buffer.remaining()).also { buffer.get(it) }
  } catch (_: RuntimeException) { null }
}

private fun nonNegative(value: Int?): MediaFact<Int> =
  if (value != null && value >= 0) MediaFact.Known(value) else MediaFact.Unknown
