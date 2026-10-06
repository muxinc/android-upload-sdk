package com.mux.video.upload.internal.standardization

import android.media.MediaExtractor
import android.os.Build
import androidx.media3.container.NalUnitUtil
import java.io.File
import java.util.concurrent.CancellationException
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
    val reader = BoundedSampleReader(limits, apiLevel, isCancelled)
    val samples = ArrayList<CompressedVideoSample>()
    fun result(status: SampleScanStatus, facts: MediaFacts = metadata.facts) =
      MediaSampleInspection(facts, status, reader.sampleCount, reader.bytesRead, reader.largestSampleBytes, reader.elapsedNanos)
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
      while (extractor.sampleTrackIndex >= 0) {
        reader.read(extractor) { time, size, flags, data ->
          val sync = flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
          samples.add(CompressedVideoSample(time, size, sync,
            if (sync) SampleRandomAccessReader.read(codec, data, size) else MediaFact.Unknown))
        }
        extractor.advance()
      }
      reader.checkBudget()
      val facts = VideoSampleFactsReader.read(samples, track.reportedDurationSeconds, metadata.facts)
        .copy(editList = videoEditList(metadata, track))
      reader.checkBudget()
      result(SampleScanStatus.Complete, facts)
    } catch (stop: SampleScanStop) { result(stop.status) }
      catch (_: CancellationException) { result(SampleScanStatus.Cancelled) }
      catch (_: Exception) { result(if (isCancelled()) SampleScanStatus.Cancelled else SampleScanStatus.Unreadable) }
      finally { extractor.release() }
  }

}

/** Match the shared video-edit policy; audio edits remain separate track observations. */
internal fun videoEditList(metadata: MediaMetadataInspection, track: TrackMetadata): MediaFact<EditList> =
  when (metadata.container.valueOrNull) {
    ContainerKind.IsoBaseMedia -> metadata.isoTracks.valueOrNull
      ?.getOrNull(track.containerIndex.valueOrNull ?: -1)?.editList ?: MediaFact.Unknown
    ContainerKind.Matroska -> MediaFact.Known(EditList.None)
    else -> MediaFact.Unknown
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
  fun read(samples: List<CompressedVideoSample>, reportedDuration: MediaFact<Double>,
    base: MediaFacts = MediaFacts(), timeline: IsoTrackTimeline? = null): MediaFacts {
    if (samples.size < 2 || samples.any { it.byteSize <= 0 }) return base
    if (timeline != null && timeline.presentationSeconds.size != samples.size) return base
    val presented = timeline?.effectiveSampleIndices
    if (presented != null && presented.size < 2) return base
    fun time(index: Int) = timeline?.presentationSeconds?.get(index) ?: (samples[index].presentationTimeUs / 1e6)
    val measured = if (timeline == null) samples else presented!!.map { samples[it].copy(presentationTimeUs =
      (maxOf(timeline?.startSeconds ?: Double.NEGATIVE_INFINITY, time(it)) * 1e6).roundToLong()) }
    val ordered = LongArray(measured.size) { measured[it].presentationTimeUs }.also { it.sort() }
    val intervals = LongArray(measured.size - 1) { ordered[it + 1] - ordered[it] }
    if (intervals.any { it <= 0 }) return base
    val span = (ordered.last() - ordered.first()) / 1e6
    if (!span.isFinite() || span <= 0) return base
    val frameInterval = span / (measured.size - 1)
    val constant = intervals.all { abs(it / 1e6 - frameInterval) <= maxOf(2e-6, frameInterval * 0.001) }
    var facts = base.copy(
      frameRate = MediaFact.Known(1 / frameInterval),
      cadence = MediaFact.Known(if (constant) Cadence.Constant else Cadence.Variable),
      timestamps = MediaFact.Known(TimestampFacts(ordered.first() / 1e6, ordered.last() / 1e6,
        measured.size.toLong(), (1 until measured.size).all {
          measured[it - 1].presentationTimeUs < measured[it].presentationTimeUs
        })),
    )
    // Only a proven effective window replaces the raw source-duration observation.
    // Unproven metadata must not extrapolate the final VFR interval.
    val duration = timeline?.let { it.endSeconds - it.startSeconds } ?: reportedDuration.valueOrNull?.takeIf {
      it.isFinite() && it > span && it - span <= intervals.max() / 1e6 * 2
    }
    if (duration != null) facts = facts.copy(averageBitrate = bitrate(measured.sumOf { it.byteSize.toLong() }, duration))
    if (!samples.first().isSync) return facts
    val boundaries = samples.indices.filter { samples[it].isSync }
    val presentedSet = presented?.toHashSet()
    fun isPresented(index: Int) = presentedSet == null || index in presentedSet
    val relevant = boundaries.indices.filter { i ->
      presentedSet == null || (boundaries[i] until (boundaries.getOrNull(i + 1) ?: samples.size)).any { isPresented(it) }
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
      val startTime = maxOf(timeline?.startSeconds ?: Double.NEGATIVE_INFINITY, time(start))
      val rawEnd = if (end < samples.size) time(end)
        else timeline?.endSeconds ?: duration?.plus(ordered.first() / 1e6) ?: return facts
      val endTime = minOf(timeline?.endSeconds ?: Double.POSITIVE_INFINITY, rawEnd)
      val seconds = endTime - startTime
      if (!seconds.isFinite() || seconds <= 0) return facts
      maxInterval = maxOf(maxInterval, seconds)
      val bytes = (start until end).sumOf { if (isPresented(it)) samples[it].byteSize.toLong() else 0L }
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
