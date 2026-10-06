package com.mux.video.upload.internal.standardization

import android.media.MediaExtractor
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToLong

internal sealed interface AudioVideoStartOffset {
  data object NotApplicable : AudioVideoStartOffset
  data class Seconds(val value: Double) : AudioVideoStartOffset
}

internal data class StandardInputTimelineFacts(
  val durationSeconds: MediaFact<Double> = MediaFact.Unknown,
  val audioVideoStartOffset: MediaFact<AudioVideoStartOffset> = MediaFact.Unknown,
  /** Effective video PTS in presentation order, excluding encoder priming outside the edit. */
  val videoPresentationSeconds: MediaFact<List<Double>> = MediaFact.Unknown,
  val videoDurationSeconds: MediaFact<Double> = MediaFact.Unknown,
  val firstAudioDurationSeconds: MediaFact<Double> = MediaFact.Unknown,
  val videoTimescale: MediaFact<Long> = MediaFact.Unknown,
)

/**
 * Effective ISO presentation time, shared by source inspection and generated-output validation.
 * Timing tables prove the final sample's duration; full platform reads prove those samples exist.
 * Fragmented, non-ISO, ambiguous, or unsupported edit timelines remain unknown. No decoding/export.
 */
internal class StandardInputTimelineInspector(
  private val limits: SampleScanLimits = SampleScanLimits(),
  private val apiLevel: Int = Build.VERSION.SDK_INT,
) {
  fun inspect(file: File, metadata: MediaMetadataInspection,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): MediaSampleInspection {
    val reader = BoundedSampleReader(limits, apiLevel, isCancelled)
    fun result(status: SampleScanStatus, timeline: StandardInputTimelineFacts = StandardInputTimelineFacts(),
      facts: MediaFacts = metadata.facts) = reader.result(facts, status, timeline)
    return try {
      reader.checkBudget()
      if (metadata.container != MediaFact.Known(ContainerKind.IsoBaseMedia) ||
        metadata.facts.videoTrackCount != MediaFact.Known(1) || metadata.facts.audioTracks == MediaFact.Unknown)
        return result(SampleScanStatus.Unsupported)
      val tracks = IsoTimelineReader.read(file, limits.maximumSamples) { reader.checkBudget() }
      val media = metadata.tracks.filter { it.kind == TrackKind.Video || it.kind == TrackKind.Audio }
      if (media.size != tracks.size || media.any { it.containerIndex.valueOrNull !in tracks.keys })
        return result(SampleScanStatus.Unsupported)
      val timingByExtractor = media.associate { it.extractorIndex to tracks.getValue(it.containerIndex.valueOrNull!!) }
      for (track in media) {
        if (track.kind == TrackKind.Audio && !timingByExtractor.getValue(track.extractorIndex).hasEdit && track.audio?.let {
            (it.encoderDelaySamples.valueOrNull ?: 0) > 0 || (it.encoderPaddingSamples.valueOrNull ?: 0) > 0
          } == true) return result(SampleScanStatus.Unsupported)
      }
      val video = media.single { it.kind == TrackKind.Video }
      val videoTime = timingByExtractor.getValue(video.extractorIndex)
      val matchers = timingByExtractor.mapValues { PlatformTimestampMatcher(it.value) }
      val counts = timingByExtractor.mapValues { 0 }.toMutableMap()
      val samples = ArrayList<CompressedVideoSample>()
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        media.forEach { extractor.selectTrack(it.extractorIndex) }
        while (extractor.sampleTrackIndex >= 0) {
          val trackIndex = extractor.sampleTrackIndex
          val timing = timingByExtractor[trackIndex] ?: throw SampleScanStop(SampleScanStatus.Unsupported)
          val index = counts.getValue(trackIndex)
          reader.read(extractor) { time, size, flags, data ->
            if (index >= timing.presentationSeconds.size || !matchers.getValue(trackIndex).matches(index, time / 1e6))
              throw SampleScanStop(SampleScanStatus.Unsupported)
            if (trackIndex == video.extractorIndex) {
              val sync = flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
              val codec = metadata.facts.videoCodec.valueOrNull
              samples.add(CompressedVideoSample((timing.presentationSeconds[index] * 1e6).roundToLong(), size, sync,
                if (sync && codec != null) SampleRandomAccessReader.read(codec, data, size) else MediaFact.Unknown))
            }
          }
          counts[trackIndex] = index + 1
          extractor.advance()
        }
        if (counts.any { (index, count) -> count != timingByExtractor.getValue(index).presentationSeconds.size })
          throw SampleScanStop(SampleScanStatus.Unreadable)
      } finally { extractor.release() }
      reader.checkBudget()
      val firstAudio = media.filter { it.kind == TrackKind.Audio }.minByOrNull { it.containerIndex.valueOrNull!! }
      val audioTime = firstAudio?.let { timingByExtractor.getValue(it.extractorIndex) }
      val offset = audioTime?.let { AudioVideoStartOffset.Seconds(it.startSeconds - videoTime.startSeconds) }
        ?: AudioVideoStartOffset.NotApplicable
      val facts = VideoSampleFactsReader.read(samples, MediaFact.Unknown, metadata.facts, videoTime)
        .copy(editList = videoEditList(metadata, video))
      reader.checkBudget()
      result(SampleScanStatus.Complete, StandardInputTimelineFacts(MediaFact.Known(tracks.values.maxOf { it.endSeconds }),
        MediaFact.Known(offset), MediaFact.Known(videoTime.effectivePresentationSeconds),
        MediaFact.Known(videoTime.endSeconds - videoTime.startSeconds),
        audioTime?.let { MediaFact.Known(it.endSeconds - it.startSeconds) } ?: MediaFact.Unknown,
        MediaFact.Known(videoTime.timescale)), facts)
    } catch (stop: SampleScanStop) { result(stop.status) }
      catch (_: CancellationException) { result(SampleScanStatus.Cancelled) }
      catch (_: LinkageError) { result(SampleScanStatus.Unsupported) }
      catch (_: Exception) { result(if (isCancelled()) SampleScanStatus.Cancelled else SampleScanStatus.Unreadable) }
  }

}


private fun checkedAdd(a: Long, b: Long): Long {
  val result = a + b
  require(((a xor result) and (b xor result)) >= 0) { "Timeline overflow" }
  return result
}

internal data class IsoTrackTimeline(
  /** Platform sample times include priming samples outside a single edit's presented range. */
  val presentationSeconds: DoubleArray,
  val startSeconds: Double,
  val endSeconds: Double,
  /** End of all sample presentation intervals, including trimmed tails, in the shifted timebase. */
  val fullEndSeconds: Double,
  val effectivePresentationSeconds: List<Double>,
  val hasEdit: Boolean,
  val timescale: Long,
  val mediaStartSeconds: Double,
  /** Decode-order indices of samples whose presentation intervals overlap the effective edit. */
  val effectiveSampleIndices: List<Int>,
)

/** A single table-to-platform timestamp convention must agree for the entire track. */
internal class PlatformTimestampMatcher(private val timing: IsoTrackTimeline) {
  private var shifted = true
  private var raw = true
  private var clamped = true
  private var firstSampleShifted = true

  fun matches(index: Int, actual: Double): Boolean {
    val effective = timing.presentationSeconds[index]
    val unshifted = effective + timing.mediaStartSeconds
    val firstRaw = timing.presentationSeconds.first() + timing.mediaStartSeconds
    val legacyShift = minOf(timing.mediaStartSeconds, maxOf(0.0, firstRaw))
    fun near(expected: Double) = actual.isFinite() && abs(actual - expected) <= 2e-6
    shifted = shifted && near(effective)
    raw = raw && near(unshifted)
    clamped = clamped && near(maxOf(0.0, effective))
    // Android Q limits the video shift to the first composition time and clamps early CTS.
    firstSampleShifted = firstSampleShifted && near(maxOf(0.0, unshifted - legacyShift))
    return shifted || raw || clamped || firstSampleShifted
  }
}

/** Timing-only extension of the existing bounded ISO box walker; never parses sample sizes or NALs. */
internal object IsoTimelineReader {
  fun read(file: File, maximumSamples: Int, checkBudget: () -> Unit = {}): Map<Int, IsoTrackTimeline> =
    RandomAccessFile(file, "r").use { input ->
      val reader = IsoContainerMetadataReader.Reader(input)
      val top = reader.boxes(0, input.length(), topLevel = true).onEach { checkBudget() }.toList()
      require(top.none { it.type == "moof" })
      val movie = top.single { it.type == "moov" }
      val children = reader.boxes(movie.payload, movie.end).toList()
      require(children.none { it.type == "mvex" })
      fun children(box: IsoMetadataBox) = reader.boxes(box.payload, box.end).toList()
      fun uint() = input.readInt().toLong() and 0xffffffffL
      fun timescale(box: IsoMetadataBox): Long {
        input.seek(box.payload)
        val version = input.readUnsignedByte()
        require(version in 0..1)
        val offset = if (version == 0) 12 else 20
        require(box.end - box.payload >= offset + 4)
        input.seek(box.payload + offset)
        return uint().also { require(it > 0) }
      }
      val movieScale = timescale(children.single { it.type == "mvhd" })
      var totalSamples = 0L
      children.filter { it.type == "trak" }.mapIndexedNotNull { index, track ->
        checkBudget()
        val trackChildren = children(track)
        val mdia = children(trackChildren.single { it.type == "mdia" })
        val handler = mdia.single { it.type == "hdlr" }
        require(handler.end - handler.payload >= 12)
        input.seek(handler.payload + 8)
        val kind = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        if (kind !in listOf("vide", "soun")) return@mapIndexedNotNull null
        val scale = timescale(mdia.single { it.type == "mdhd" })
        val tables = children(children(mdia.single { it.type == "minf" }).single { it.type == "stbl" })
        fun runs(box: IsoMetadataBox, composition: Boolean): List<Pair<Int, Long>> {
          require(box.end - box.payload >= 8)
          input.seek(box.payload)
          val version = input.readUnsignedByte()
          require(version in (if (composition) 0..1 else 0..0))
          require(input.readUnsignedByte() == 0 && input.readUnsignedShort() == 0)
          val entries = uint()
          if (entries > maximumSamples) throw SampleScanStop(SampleScanStatus.LimitExceeded)
          require(entries > 0 && box.end - box.payload == 8 + entries * 8)
          var samples = 0L
          return List(entries.toInt()) {
            checkBudget()
            val n = uint()
            // Match Media3/Android's signed composition offsets, including QuickTime v0 files.
            // Full extractor reads below must agree before any timeline becomes Known.
            val value = if (composition) input.readInt().toLong() else uint()
            samples += n
            if (samples > maximumSamples) throw SampleScanStop(SampleScanStatus.LimitExceeded)
            require(n > 0 && (composition || value > 0))
            n.toInt() to value
          }
        }
        val durations = runs(tables.single { it.type == "stts" }, false)
        val sampleCount = durations.sumOf { it.first }
        totalSamples += sampleCount
        if (totalSamples > maximumSamples) throw SampleScanStop(SampleScanStatus.LimitExceeded)
        val deltas = LongArray(sampleCount)
        var position = 0
        for ((count, delta) in durations) repeat(count) { deltas[position++] = delta }
        val offsets = LongArray(sampleCount)
        val composition = tables.filter { it.type == "ctts" }
        require(composition.size <= 1)
        composition.singleOrNull()?.let {
          val entries = runs(it, true)
          require(entries.sumOf { it.first } == sampleCount)
          position = 0
          for ((count, offset) in entries) repeat(count) { offsets[position++] = offset }
        }
        var decodeTime = 0L
        val starts = LongArray(sampleCount) { i ->
          checkBudget()
          val pts = checkedAdd(decodeTime, offsets[i])
          decodeTime = checkedAdd(decodeTime, deltas[i])
          pts
        }
        val ends = LongArray(sampleCount) { checkedAdd(starts[it], deltas[it]) }
        require(starts.distinct().size == sampleCount)
        // Single unit-rate edits cover phone encoder priming and simple trims. Complex edits are
        // unproven here, including empty leading edits; do not guess how the platform applies them.
        var mediaStart = 0.0
        var editDuration: Double? = null
        val editBoxes = trackChildren.filter { it.type == "edts" }
        require(editBoxes.size <= 1)
        editBoxes.singleOrNull()?.let {
          val list = children(it).single { it.type == "elst" }
          require(list.end - list.payload >= 8)
          input.seek(list.payload)
          val version = input.readUnsignedByte()
          require(version in 0..1 && input.readUnsignedByte() == 0 && input.readUnsignedShort() == 0)
          val count = uint()
          if (count > 1) throw SampleScanStop(SampleScanStatus.Unsupported)
          require(list.end - list.payload == 8 + count * (if (version == 0) 12 else 20))
          if (count == 1L) {
            val duration = if (version == 0) uint() else input.readLong()
            val time = if (version == 0) input.readInt().toLong() else input.readLong()
            require(duration > 0 && time >= 0 && input.readInt() == 65536)
            mediaStart = time.toDouble() / scale
            editDuration = duration.toDouble() / movieScale
          }
        }
        val rawStart = starts.min().toDouble() / scale
        val rawEnd = ends.max().toDouble() / scale
        val start = maxOf(0.0, rawStart - mediaStart)
        val end = editDuration ?: rawEnd
        require(start.isFinite() && end.isFinite() && end > start)
        if (editDuration != null) {
          // Table coverage must reach the edit endpoint; movie/track timescale rounding is bounded.
          require(rawStart <= mediaStart + 2.0 / scale &&
            rawEnd + 2.0 / scale + 1.0 / movieScale >= mediaStart + end)
        } else require(rawStart >= 0)
        val platformTimes = DoubleArray(sampleCount) { starts[it].toDouble() / scale - mediaStart }
        val effectiveIndices = starts.indices.filter { ends[it].toDouble() / scale > mediaStart && platformTimes[it] < end }
        val effectiveTimes = effectiveIndices.map { maxOf(start, platformTimes[it]) }.sorted()
        require(effectiveTimes.isNotEmpty())
        index to IsoTrackTimeline(platformTimes, start, end, rawEnd - mediaStart, effectiveTimes, editDuration != null, scale, mediaStart, effectiveIndices)
      }.toMap()
    }
}
