package com.mux.video.upload.internal.standardization

import androidx.media3.container.NalUnitUtil
import kotlin.math.abs
import kotlin.math.roundToLong

internal enum class SampleScanStatus { Complete, Unsupported, Unreadable, LimitExceeded, Cancelled }
internal data class SampleScanLimits(
  val maximumSampleBytes: Int = 4 * 1024 * 1024,
  val maximumSamples: Int = 250_000,
  val maximumReadBytes: Long = 1024L * 1024 * 1024,
  /** Checked between platform reads; a blocking native call cannot be preempted. */
  val maximumElapsedNanos: Long = 5_000_000_000,
) {
  init {
    require(maximumSampleBytes > 0 && maximumSamples > 0 && maximumReadBytes > 0 && maximumElapsedNanos > 0)
  }
}

internal data class MediaSampleInspection(
  val facts: MediaFacts,
  val status: SampleScanStatus,
  val sampleCount: Int = 0,
  val bytesRead: Long = 0,
  val largestSampleBytes: Int = 0,
  val elapsedNanos: Long = 0,
  /** Proven ISO timeline from this scan; unknown for other containers or incomplete reads. */
  val timeline: StandardInputTimelineFacts = StandardInputTimelineFacts(),
)

internal data class CompressedVideoSample(
  val byteSize: Int,
  val isSync: Boolean,
  val randomAccess: MediaFact<GopStructure> = MediaFact.Unknown,
)

/** Only sync samples need relevant NAL inspection. Unrecognized framing or syntax stays unknown. */
internal object SampleRandomAccessReader {
  fun read(codec: VideoCodec, data: ByteArray, size: Int): MediaFact<GopStructure> = try {
    inspect(codec, data, size)
  } catch (_: LinkageError) { MediaFact.Unknown }
    catch (_: RuntimeException) { MediaFact.Unknown }

  private fun inspect(codec: VideoCodec, data: ByteArray, size: Int): MediaFact<GopStructure> {
    if (size !in 1..data.size) return MediaFact.Unknown
    val flags = BooleanArray(3)
    var prefix = NalUnitUtil.findNalUnit(data, 0, size, flags)
    if (prefix !in 0..1 || (prefix == 1 && data[0] != 0.toByte())) return MediaFact.Unknown
    var slices = 0
    var idr = true
    var hasIdr = false
    var open = false
    var count = 0
    while (prefix < size) {
      if (++count > 4096) return MediaFact.Unknown
      val start = prefix + 3
      NalUnitUtil.clearPrefixFlags(flags)
      val next = NalUnitUtil.findNalUnit(data, start, size, flags)
      if (start >= next || data[start].toInt() and 128 != 0) return MediaFact.Unknown
      when (codec) {
        VideoCodec.H264 -> {
          val type = data[start].toInt() and 31
          when (type) {
            1, 5 -> {
              if (next - start < 2 || (type == 5 && data[start].toInt() and 96 == 0)) return MediaFact.Unknown
              slices++; idr = idr && type == 5; hasIdr = hasIdr || type == 5
            }
            2, 3, 4 -> return MediaFact.Unknown
            else -> Unit // Non-VCL NALs do not establish or invalidate random-access kind.
          }
        }
        VideoCodec.Hevc -> {
          if (next - start < 2 || data[start].toInt() and 1 != 0 ||
            data[start + 1].toInt() and 248 != 0 || data[start + 1].toInt() and 7 == 0)
            return MediaFact.Unknown
          val type = (data[start].toInt() and 126) shr 1
          when (type) {
            in 0..9, in 16..21 -> {
              if (next - start < 3) return MediaFact.Unknown
              slices++
              idr = idr && type in 19..20
              hasIdr = hasIdr || type in 19..20
              if (type in 16..18 || type == 21) open = true
            }
            in 10..15, in 22..31 -> return MediaFact.Unknown
            else -> Unit // Includes EOS/EOB and Dolby RPU signaling; HDR eligibility is separate.
          }
        }
        else -> return MediaFact.Unknown
      }
      prefix = next
    }
    return when {
      slices == 0 -> MediaFact.Unknown
      open && hasIdr -> MediaFact.Unknown
      open -> MediaFact.Known(GopStructure.Open)
      idr -> MediaFact.Known(GopStructure.ClosedWithIdr)
      codec == VideoCodec.H264 && !hasIdr -> MediaFact.Known(GopStructure.Open)
      else -> MediaFact.Unknown
    }
  }
}

internal object VideoSampleFactsReader {
  fun read(samples: List<CompressedVideoSample>, timeline: IsoTrackTimeline, base: MediaFacts = MediaFacts()): MediaFacts {
    if (samples.size < 2 || samples.any { it.byteSize <= 0 }) return base
    if (timeline.presentationSeconds.size != samples.size) return base
    val presented = timeline.effectiveSampleIndices
    fun time(index: Int) = timeline.presentationSeconds[index]
    val ordered = LongArray(presented.size) { (maxOf(timeline.startSeconds, time(presented[it])) * 1e6).roundToLong() }
    if (ordered.size < 2) return base
    val matchesDecodeOrder = (1 until ordered.size).all { ordered[it - 1] < ordered[it] }
    ordered.sort()
    val intervals = LongArray(ordered.size - 1) { ordered[it + 1] - ordered[it] }
    if (intervals.any { it <= 0 }) return base
    val span = (ordered.last() - ordered.first()) / 1e6
    if (!span.isFinite() || span <= 0) return base
    val frameInterval = span / (ordered.size - 1)
    val constant = intervals.all { abs(it / 1e6 - frameInterval) <= maxOf(2e-6, frameInterval * 0.001) }
    var facts = base.copy(
      frameRate = MediaFact.Known(1 / frameInterval),
      cadence = MediaFact.Known(if (constant) Cadence.Constant else Cadence.Variable),
      timestamps = MediaFact.Known(TimestampFacts(ordered.first() / 1e6, ordered.last() / 1e6,
        ordered.size.toLong(), matchesDecodeOrder)),
    )
    // Whole-track bytes include preroll and trimmed tails, over their full proven sample span.
    val duration = timeline.fullEndSeconds - timeline.presentationSeconds.min()
    facts = facts.copy(averageBitrate = bitrate(samples.sumOf { it.byteSize.toLong() }, duration))
    if (!samples.first().isSync) return facts
    val boundaries = samples.indices.filter { samples[it].isSync }
    val presentedSet = presented.toHashSet()
    val relevant = boundaries.indices.filter { i ->
      (boundaries[i] until (boundaries.getOrNull(i + 1) ?: samples.size)).any { it in presentedSet }
    }
    // Keep the original IDR boundary, including decode preroll outside the effective edit.
    val kinds = relevant.map { samples[boundaries[it]].randomAccess }
    val open = kinds.any { it == MediaFact.Known(GopStructure.Open) }
    val proven = kinds.all { it == MediaFact.Known(GopStructure.ClosedWithIdr) }
    facts = facts.copy(gopStructure = when {
      open -> MediaFact.Known(GopStructure.Open)
      proven -> MediaFact.Known(GopStructure.ClosedWithIdr)
      else -> MediaFact.Unknown
    })
    var maxBytes = 0L
    var maxRate = 0L
    var maxInterval = 0.0
    for (i in relevant) {
      val start = boundaries[i]
      val end = boundaries.getOrNull(i + 1) ?: samples.size
      val startTime = maxOf(timeline.startSeconds, time(start))
      val rawEnd = if (end < samples.size) time(end) else timeline.fullEndSeconds
      val endTime = minOf(timeline.endSeconds, rawEnd)
      val seconds = endTime - startTime
      if (!seconds.isFinite() || seconds <= 0) return facts
      maxInterval = maxOf(maxInterval, seconds)
      // Count the complete intersecting GOP, including its IDR and possible decode dependencies
      // outside the edit. Without a reference graph, this is a conservative payload bound.
      val bytes = (start until end).sumOf { samples[it].byteSize.toLong() }
      maxBytes = maxOf(maxBytes, bytes)
      maxRate = maxOf(maxRate, bitrate(bytes, rawEnd - time(start)).valueOrNull ?: return facts)
    }
    return facts.copy(maximumKeyframeIntervalSeconds = MediaFact.Known(maxInterval),
      // Leading pictures in open/unproven GOPs cannot be assigned reliably to a byte window.
      maximumGopByteSize = if (proven) MediaFact.Known(maxBytes) else MediaFact.Unknown,
      maximumGopBitrate = if (proven) MediaFact.Known(maxRate) else MediaFact.Unknown)
  }

  private fun bitrate(bytes: Long, seconds: Double): MediaFact<Long> {
    val rate = bytes.toDouble() * 8 / seconds
    return if (rate.isFinite() && rate > 0 && rate < Long.MAX_VALUE.toDouble())
      MediaFact.Known(rate.roundToLong()) else MediaFact.Unknown
  }
}
