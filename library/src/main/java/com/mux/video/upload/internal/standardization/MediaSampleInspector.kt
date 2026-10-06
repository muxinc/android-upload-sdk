package com.mux.video.upload.internal.standardization

import android.media.MediaExtractor
import android.os.Build
import androidx.media3.container.NalUnitUtil
import java.io.File
import java.nio.ByteBuffer
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
  val sampleCount: Int,
  val bytesRead: Long,
  val largestSampleBytes: Int,
  val elapsedNanos: Long,
)

internal data class CompressedVideoSample(
  val presentationTimeUs: Long,
  val byteSize: Int,
  val isSync: Boolean,
  val randomAccess: MediaFact<GopStructure> = MediaFact.Unknown,
)

/** Separate from metadata inspection. No decoding, exporting, or effective timeline calculation. */
internal class MediaSampleInspector(private val limits: SampleScanLimits = SampleScanLimits(),
  private val apiLevel: Int = Build.VERSION.SDK_INT) {
  fun inspect(file: File, metadata: MediaMetadataInspection,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): MediaSampleInspection {
    val started = System.nanoTime()
    val samples = ArrayList<CompressedVideoSample>()
    var bytes = 0L
    var largestSample = 0
    fun result(status: SampleScanStatus, facts: MediaFacts = metadata.facts) =
      MediaSampleInspection(facts, status, samples.size, bytes, largestSample, System.nanoTime() - started)
    if (isCancelled()) return result(SampleScanStatus.Cancelled)
    val track = metadata.tracks.filter { it.kind == TrackKind.Video }.singleOrNull()
      ?: return result(SampleScanStatus.Unsupported)
    if (metadata.facts.videoTrackCount != MediaFact.Known(1) ||
      metadata.facts.videoCodec.valueOrNull !in listOf(VideoCodec.H264, VideoCodec.Hevc))
      return result(SampleScanStatus.Unsupported)
    if (metadata.container == MediaFact.Known(ContainerKind.IsoBaseMedia) &&
      track.containerIndex == MediaFact.Unknown) return result(SampleScanStatus.Unsupported)
    val codec = metadata.facts.videoCodec.valueOrNull!!
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      extractor.selectTrack(track.extractorIndex)
      val data = ByteArray(limits.maximumSampleBytes)
      val buffer = ByteBuffer.wrap(data)
      while (extractor.sampleTrackIndex >= 0) {
        if (isCancelled()) return result(SampleScanStatus.Cancelled)
        if (samples.size >= limits.maximumSamples || bytes >= limits.maximumReadBytes ||
          System.nanoTime() - started >= limits.maximumElapsedNanos) return result(SampleScanStatus.LimitExceeded)
        val flags = extractor.sampleFlags
        // Partial frames and encrypted bytes cannot establish complete pictures or GOPs.
        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC.inv() != 0) return result(SampleScanStatus.Unsupported)
        val expectedSize = if (apiLevel >= 28) extractor.sampleSize else null
        if (expectedSize != null && (expectedSize <= 0 || expectedSize > data.size ||
            expectedSize > limits.maximumReadBytes - bytes)) return result(SampleScanStatus.LimitExceeded)
        buffer.clear()
        // API 23–27 has no size query. Cap the native buffer's capacity as well as its limit.
        val remaining = limits.maximumReadBytes - bytes
        val readBuffer = if (remaining < data.size) ByteBuffer.wrap(data, 0, remaining.toInt()).slice() else buffer
        val size = extractor.readSampleData(readBuffer, 0)
        if (size <= 0 || size > data.size || size.toLong() > limits.maximumReadBytes - bytes ||
          (expectedSize != null && size.toLong() != expectedSize)) return result(SampleScanStatus.LimitExceeded)
        bytes += size
        largestSample = maxOf(largestSample, size)
        val sync = flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
        samples.add(CompressedVideoSample(extractor.sampleTime, size, sync,
          if (sync) SampleRandomAccessReader.read(codec, data, size) else MediaFact.Unknown))
        extractor.advance()
      }
      if (isCancelled()) return result(SampleScanStatus.Cancelled)
      if (System.nanoTime() - started >= limits.maximumElapsedNanos) return result(SampleScanStatus.LimitExceeded)
      // Match the shared video-edit policy. Audio edits remain separate track observations.
      val edit = metadata.isoTracks.valueOrNull?.getOrNull(track.containerIndex.valueOrNull ?: -1)?.editList
        ?: MediaFact.Unknown
      val facts = VideoSampleFactsReader.read(samples, track.reportedDurationSeconds, metadata.facts).copy(editList = edit)
      if (isCancelled()) return result(SampleScanStatus.Cancelled)
      if (System.nanoTime() - started >= limits.maximumElapsedNanos) return result(SampleScanStatus.LimitExceeded)
      result(SampleScanStatus.Complete, facts)
    } catch (_: Exception) {
      result(SampleScanStatus.Unreadable)
    } finally {
      extractor.release()
    }
  }
}

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
            6, 7, 8, 9, 12 -> Unit
            else -> return MediaFact.Unknown
          }
        }
        VideoCodec.Hevc -> {
          if (next - start < 3 || data[start].toInt() and 1 != 0 ||
            data[start + 1].toInt() and 248 != 0 || data[start + 1].toInt() and 7 == 0)
            return MediaFact.Unknown
          val type = (data[start].toInt() and 126) shr 1
          when (type) {
            in 0..9, in 16..21 -> {
              slices++
              idr = idr && type in 19..20
              hasIdr = hasIdr || type in 19..20
              if (type in 16..18 || type == 21) open = true
            }
            32, 33, 34, 35, 38, 39, 40 -> Unit
            else -> return MediaFact.Unknown
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
  fun read(samples: List<CompressedVideoSample>, reportedDuration: MediaFact<Double>,
    base: MediaFacts = MediaFacts()): MediaFacts {
    if (samples.size < 2 || samples.any { it.byteSize <= 0 }) return base
    val ordered = LongArray(samples.size) { samples[it].presentationTimeUs }.also { it.sort() }
    val intervals = LongArray(samples.size - 1) { ordered[it + 1] - ordered[it] }
    if (intervals.any { it <= 0 }) return base
    val span = (ordered.last() - ordered.first()) / 1e6
    if (!span.isFinite() || span <= 0) return base
    val frameInterval = span / (samples.size - 1)
    val constant = intervals.all { abs(it / 1e6 - frameInterval) <= maxOf(2e-6, frameInterval * 0.001) }
    var facts = base.copy(
      frameRate = MediaFact.Known(1 / frameInterval),
      cadence = MediaFact.Known(if (constant) Cadence.Constant else Cadence.Variable),
      timestamps = MediaFact.Known(TimestampFacts(ordered.first() / 1e6, ordered.last() / 1e6,
        samples.size.toLong(), (1 until samples.size).all {
          samples[it - 1].presentationTimeUs < samples[it].presentationTimeUs
        })),
    )
    // Raw track duration is a bitrate denominator, not proof of effective duration or A/V sync.
    // Reject inconsistent metadata instead of extrapolating an unobserved final VFR interval.
    val duration = reportedDuration.valueOrNull?.takeIf {
      it.isFinite() && it > span && it - span <= intervals.max() / 1e6 * 2
    }
    if (duration != null) facts = facts.copy(averageBitrate = bitrate(samples.sumOf { it.byteSize.toLong() }, duration))
    if (!samples.first().isSync) return facts
    val boundaries = samples.indices.filter { samples[it].isSync }
    val kinds = boundaries.map { samples[it].randomAccess }
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
    for (i in boundaries.indices) {
      val start = boundaries[i]
      val end = boundaries.getOrNull(i + 1) ?: samples.size
      val startTime = samples[start].presentationTimeUs / 1e6
      val endTime = if (end < samples.size) samples[end].presentationTimeUs / 1e6
        else duration?.plus(ordered.first() / 1e6) ?: return facts
      val seconds = endTime - startTime
      if (!seconds.isFinite() || seconds <= 0) return facts
      maxInterval = maxOf(maxInterval, seconds)
      val bytes = samples.subList(start, end).sumOf { it.byteSize.toLong() }
      maxBytes = maxOf(maxBytes, bytes)
      maxRate = maxOf(maxRate, bitrate(bytes, seconds).valueOrNull ?: return facts)
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
