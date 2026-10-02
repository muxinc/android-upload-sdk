package com.mux.video.upload.internal.standardization

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.Track
import com.google.common.primitives.ImmutableLongArray
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal data class IsoTrackTimeline(
  val startSeconds: Double,
  val endSeconds: Double,
  val movieDurationSeconds: Double,
  val movieTickSeconds: Double,
  val hasEdits: Boolean,
)

/** Bounds metadata allocations, then delegates composition timestamps and edits to Media3. */
internal class IsoTimelineMetadataReader(private val input: RandomAccessFile) {
  private data class Timebase(val scale: Long, val duration: Long)
  private data class Edit(val duration: Long, val mediaTime: Long)

  fun read(movieHeader: IsoMetadataBox?, mediaHeader: IsoMetadataBox?,
    editChildren: List<IsoMetadataBox>?, tables: List<IsoMetadataBox>): MediaFact<IsoTrackTimeline> = try {
    val movie = timebase(movieHeader ?: return MediaFact.Unknown)
    val media = timebase(mediaHeader ?: return MediaFact.Unknown)
    val selected = tables.filter { it.type in TABLE_TYPES }
    require(selected.map { it.type }.distinct().size == selected.size)
    require(selected.sumOf { it.end - it.payload + 8 } <= MAX_TABLE_BYTES)
    val data = selected.associate { it.type to bytes(it) }
    val samples = sampleCount(data)
    require(samples in 1..MAX_SAMPLES)
    require(validateRuns(data.getValue("stts"), samples, composition = false) == media.duration)
    val edits = editChildren?.let {
      require(it.size == 1 && it.single().type == "elst")
      edits(it.single())
    }
    // Without an edit, Media3's duration uses the last decoded composition offset, not the
    // maximum presentation end. Only a uniform nonnegative offset establishes that end here.
    data["ctts"]?.let { validateRuns(it, samples, composition = true, unedited = edits == null) }
    val stbl = Mp4Box.ContainerBox(Mp4Box.TYPE_stbl, 0)
    data.forEach { (type, bytes) ->
      val code = ByteBuffer.wrap(type.toByteArray(Charsets.US_ASCII)).int
      stbl.add(Mp4Box.LeafBox(code, ParsableByteArray(bytes)))
    }
    // Request generic timing, so Media3 applies edits to timestamps rather than translating
    // short audio trims into codec-specific gapless delay/padding. No codec parsing is needed.
    val track = Track.Builder().setType(C.TRACK_TYPE_UNKNOWN).setTimescale(media.scale)
      .setMovieTimescale(movie.scale).setFormat(Format.Builder().setMaxNumReorderSamples(samples).build())
      .setEditListDurations(edits?.let { ImmutableLongArray.copyOf(it.map(Edit::duration)) })
      .setEditListMediaTimes(edits?.let { ImmutableLongArray.copyOf(it.map(Edit::mediaTime)) }).build()
    val parsed = BoxParser.parseStbl(track, stbl, GaplessInfoHolder(), false)
    require(parsed.timestampsUs.isNotEmpty() && parsed.durationUs > 0)
    if (edits == null) require(parsed.sampleCount == samples)
    val emptyDuration = if (edits?.size == 2) edits.first().duration.toDouble() / movie.scale else 0.0
    val first = parsed.timestampsUs.min().toDouble() / 1_000_000
    // Edit preroll is needed for decoding but is outside the effective presentation interval.
    val start = if (edits == null) first else maxOf(first, emptyDuration)
    val end = parsed.durationUs.toDouble() / 1_000_000
    require(start.isFinite() && start >= 0 && end > start)
    MediaFact.Known(IsoTrackTimeline(start, end, movie.duration.toDouble() / movie.scale,
      1.0 / movie.scale, edits != null))
  } catch (_: LinkageError) { MediaFact.Unknown }
    catch (_: Exception) { MediaFact.Unknown }

  /** A single buffered read per box; Media3 expects the ordinary eight-byte box header. */
  private fun bytes(box: IsoMetadataBox): ByteArray {
    val length = box.end - box.payload
    require(length in 0..MAX_TABLE_BYTES)
    val result = ByteArray(length.toInt() + 8)
    ByteBuffer.wrap(result).putInt(result.size).put(box.type.toByteArray(Charsets.US_ASCII))
    input.seek(box.payload)
    input.readFully(result, 8, length.toInt())
    return result
  }

  private fun timebase(box: IsoMetadataBox): Timebase {
    val data = ByteBuffer.wrap(bytes(box))
    val version = data.getInt(8)
    require(version == 0 || version == 0x01000000)
    data.position(if (version == 0) 20 else 28)
    val scale = data.int.toLong() and 0xffffffffL
    val duration = if (version == 0) data.int.toLong() and 0xffffffffL else data.long
    require(scale > 0 && duration in 1..MAX_EXACT_TIME && (version != 0 || duration != 0xffffffffL))
    return Timebase(scale, duration)
  }

  /** Reject inconsistent or allocation-driving counts before the reusable parser sees them. */
  private fun validateRuns(bytes: ByteArray, samples: Int, composition: Boolean, unedited: Boolean = false): Long {
    val data = ByteBuffer.wrap(bytes)
    data.position(8)
    val version = data.int
    require(version == 0 || (composition && version == 0x01000000))
    val rows = data.int
    require(rows in 1..samples && data.remaining().toLong() == rows.toLong() * 8)
    val firstValue = data.getInt(20)
    var count = 0L
    var duration = 0L
    repeat(rows) {
      val run = data.int
      val value = data.int // Media3 also accepts signed composition offsets in version zero.
      require(run > 0 && (composition || value > 0))
      if (composition && unedited) require(value >= 0 && value == firstValue)
      count += run
      require(count <= samples)
      if (!composition) duration += run.toLong() * value
    }
    require(count == samples.toLong() && duration <= MAX_EXACT_TIME)
    return duration
  }

  private fun sampleCount(data: Map<String, ByteArray>): Int {
    require(data.keys.count { it == "stsz" || it == "stz2" } == 1)
    val compact = "stz2" in data
    val bytes = ByteBuffer.wrap(data.getValue(if (compact) "stz2" else "stsz"))
    bytes.position(8)
    require(bytes.int == 0)
    val size = bytes.int.toLong() and 0xffffffffL
    val count = bytes.int
    require(count in 1..MAX_SAMPLES)
    val remaining = if (!compact) { if (size > 0) 0L else count.toLong() * 4 } else {
      require(size in setOf(4L, 8L, 16L))
      (count * size + 7) / 8
    }
    require(bytes.remaining().toLong() == remaining)
    return count
  }

  private fun edits(box: IsoMetadataBox): List<Edit> {
    val data = ByteBuffer.wrap(bytes(box))
    data.position(8)
    val version = data.int
    require(version == 0 || version == 0x01000000)
    val count = data.int
    require(count in 1..2 && data.remaining() == count * if (version == 0) 12 else 20)
    val edits = List(count) {
      val duration = if (version == 0) data.int.toLong() and 0xffffffffL else data.long
      val time = if (version == 0) data.int.toLong() else data.long
      require(duration in 1..MAX_EXACT_TIME && time in -1..MAX_EXACT_TIME && data.int == 65536)
      Edit(duration, time)
    }
    require(edits.last().mediaTime >= 0 && (count == 1 || edits.first().mediaTime == -1L))
    return edits
  }

  companion object {
    // Per-track limits allow dense VFR/B-frame tables without a shared row budget.
    // Media3 materializes per-sample metadata; cap that allocation independently of file size.
    private const val MAX_SAMPLES = 500_000
    private const val MAX_TABLE_BYTES = 8L * 1024 * 1024
    private const val MAX_EXACT_TIME = 1L shl 53
    private val TABLE_TYPES = setOf("stts", "ctts", "stsz", "stz2", "stsc", "stco", "co64", "stss")
  }
}
