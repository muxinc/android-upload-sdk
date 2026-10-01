package com.mux.video.upload.internal.standardization

/** Unproven facts remain unknown; absence of metadata is not a compliant value. */
internal sealed interface MediaFact<out T> {
  data class Known<T>(val value: T) : MediaFact<T>
  data object Unknown : MediaFact<Nothing>
}

internal val <T> MediaFact<T>.valueOrNull: T?
  get() = (this as? MediaFact.Known<T>)?.value

internal enum class VideoCodec { H264, Hevc, Other }
internal enum class VideoProfile { H264Baseline, H264Main, H264High, HevcMain, HevcMain10, Other }
internal enum class ChromaSubsampling { Yuv420, Other }
internal data class PixelFormat(val bitDepth: Int, val chromaSubsampling: ChromaSubsampling)
internal enum class DynamicRange { Sdr, Hlg, Pq, DolbyVision, OtherHdr }
internal enum class GopStructure { ClosedWithIdr, ClosedWithoutIdr, Open }
internal enum class EditList { None, Simple, Complex }
internal enum class Cadence { Constant, Variable }
internal enum class AudioChannelLayout { Mono, Stereo, FivePointOne, Other }

internal sealed interface AudioFormat {
  data class Aac(val layout: AudioChannelLayout) : AudioFormat
  data object OtherCodec : AudioFormat
}

/** Track order is container order, so conversion can select the first audio track. */
internal data class AudioTrack(val format: MediaFact<AudioFormat> = MediaFact.Unknown)

internal data class Dimensions(val width: Int, val height: Int) {
  val longSide: Int get() = maxOf(width, height)
  val shortSide: Int get() = minOf(width, height)
  val isValid: Boolean get() = width > 0 && height > 0

  fun fitsWithin(bounds: Dimensions): Boolean =
    longSide <= bounds.longSide && shortSide <= bounds.shortSide
}

internal data class TimestampFacts(
  val firstPresentationSeconds: Double,
  val lastPresentationSeconds: Double,
  val sampleCount: Long,
  val isMonotonic: Boolean,
)

/** Normalized evidence only. Inspection, hardware probing, files, and Android APIs live elsewhere. */
internal data class MediaFacts(
  val videoCodec: MediaFact<VideoCodec> = MediaFact.Unknown,
  val videoProfile: MediaFact<VideoProfile> = MediaFact.Unknown,
  val encodedDimensions: MediaFact<Dimensions> = MediaFact.Unknown,
  val displayDimensions: MediaFact<Dimensions> = MediaFact.Unknown,
  val rotationDegrees: MediaFact<Int> = MediaFact.Unknown,
  val videoTrackCount: MediaFact<Int> = MediaFact.Unknown,
  val frameRate: MediaFact<Double> = MediaFact.Unknown,
  val cadence: MediaFact<Cadence> = MediaFact.Unknown,
  val timestamps: MediaFact<TimestampFacts> = MediaFact.Unknown,
  val durationSeconds: MediaFact<Double> = MediaFact.Unknown,
  val audioVideoStartOffsetSeconds: MediaFact<Double> = MediaFact.Unknown,
  val averageBitrate: MediaFact<Long> = MediaFact.Unknown,
  val maximumGopBitrate: MediaFact<Long> = MediaFact.Unknown,
  val maximumGopByteSize: MediaFact<Long> = MediaFact.Unknown,
  val maximumKeyframeIntervalSeconds: MediaFact<Double> = MediaFact.Unknown,
  val gopStructure: MediaFact<GopStructure> = MediaFact.Unknown,
  val pixelFormat: MediaFact<PixelFormat> = MediaFact.Unknown,
  val dynamicRange: MediaFact<DynamicRange> = MediaFact.Unknown,
  val audioTracks: MediaFact<List<AudioTrack>> = MediaFact.Unknown,
  val editList: MediaFact<EditList> = MediaFact.Unknown,
)
