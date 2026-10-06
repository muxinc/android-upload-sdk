package com.mux.video.upload.internal.standardization

import kotlin.math.floor

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

  fun scaledToFit(bounds: Dimensions): Dimensions? {
    // A one-pixel axis cannot produce 4:2:0 output without upscaling.
    if (!isValid || shortSide < 2) return null
    val scale = minOf(1.0, bounds.longSide.toDouble() / longSide, bounds.shortSide.toDouble() / shortSide)
    // Match Swift's nearest even alignment, capped at the source axis to prevent odd-size upscaling.
    fun aligned(axis: Int): Int = minOf(axis / 2 * 2, maxOf(2, (floor(axis * scale / 2 + 0.5) * 2).toInt()))
    return Dimensions(aligned(width), aligned(height))
  }
}

internal data class TimestampFacts(
  val firstPresentationSeconds: Double,
  val lastPresentationSeconds: Double,
  val sampleCount: Long,
  /** False is normal for B-frame reordering and does not imply invalid presentation timestamps. */
  val presentationOrderMatchesDecodeOrder: Boolean,
)

/**
 * Normalized evidence shared with inspection, conversion, and output validation. The policy reads only
 * its published predicates; profile, encoded geometry/rotation, and timeline/cadence also serve those
 * later stages. Display dimensions already include orientation. No file or platform APIs belong here.
 */
internal data class MediaFacts(
  val videoCodec: MediaFact<VideoCodec> = MediaFact.Unknown,
  val videoProfile: MediaFact<VideoProfile> = MediaFact.Unknown,
  val encodedDimensions: MediaFact<Dimensions> = MediaFact.Unknown,
  val displayDimensions: MediaFact<Dimensions> = MediaFact.Unknown,
  /**
   * Shared Swift transform convention, opposite to Android/Media3 for quarter turns.
   * tkhd [0, 1, -1, 0] / Android KEY_ROTATION = 90 becomes 270.
   * Convert a known value back to Android with (360 - degrees) % 360.
   */
  val rotationDegrees: MediaFact<Int> = MediaFact.Unknown,
  val videoTrackCount: MediaFact<Int> = MediaFact.Unknown,
  /** Measured nominal rate for policy; it does not imply constant presentation intervals. */
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
