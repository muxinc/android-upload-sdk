package com.mux.video.upload.internal.standardization

import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/** Presentation bounds derived from timing tables and a supported unit-rate edit, not sample payloads. */
internal data class IsoTrackTimeline(
  val startSeconds: Double,
  val endSeconds: Double,
  val movieDurationSeconds: Double,
  val movieTickSeconds: Double,
  val hasEdits: Boolean,
)

/** Bounded run-length timing metadata. Cadence, GOP and compressed-sample evidence are separate. */
internal class IsoTimelineMetadataReader(private val input: RandomAccessFile) {
  private data class Timebase(val scale: Long, val duration: Long)
  private data class Run(val count: Long, val value: Long)
  private data class Edit(val duration: Long, val mediaTime: Long)
  private var remainingRows = MAX_TIMING_ROWS

  fun read(movieHeader: IsoMetadataBox?, mediaHeader: IsoMetadataBox?,
    editChildren: List<IsoMetadataBox>?, tables: List<IsoMetadataBox>): MediaFact<IsoTrackTimeline> = try {
    val movie = timebase(movieHeader ?: return MediaFact.Unknown)
    val media = timebase(mediaHeader ?: return MediaFact.Unknown)
    val decode = runs(tables.single { it.type == "stts" }, composition = false)
    val totalSamples = decode.fold(0L) { sum, run -> boundedAdd(sum, run.count) }
    require(totalSamples > 0 && totalSamples == sampleCount(tables))
    val compositionBoxes = tables.filter { it.type == "ctts" }
    require(compositionBoxes.size <= 1)
    val composition = compositionBoxes.singleOrNull()?.let { runs(it, composition = true) }
      ?: listOf(Run(totalSamples, 0))
    require(composition.fold(0L) { sum, run -> boundedAdd(sum, run.count) } == totalSamples)
    val decodeDuration = decode.fold(0L) { sum, run -> boundedAdd(sum, boundedMultiply(run.count, run.value)) }
    require(decodeDuration == media.duration && decodeDuration <= MAX_EXACT_TIME)

    val edits = editChildren?.let {
      require(it.size == 1 && it.single().type == "elst")
      edits(it.single())
    }
    val mediaEdit = edits?.last()
    val emptyDuration = if (edits?.size == 2) edits.first().duration.toDouble() / movie.scale else 0.0
    val lower = mediaEdit?.mediaTime?.toDouble() ?: Double.NEGATIVE_INFINITY
    val upper = mediaEdit?.let { lower + it.duration.toDouble() * media.scale / movie.scale }
      ?: Double.POSITIVE_INFINITY
    require(mediaEdit == null || (lower >= 0 && upper > lower && upper <= MAX_EXACT_TIME))

    var decodeIndex = 0
    var compositionIndex = 0
    var decodeRemaining = decode.first().count
    var compositionRemaining = composition.first().count
    var dts = 0L
    var first = Double.POSITIVE_INFINITY
    var end = Double.NEGATIVE_INFINITY
    while (decodeIndex < decode.size && compositionIndex < composition.size) {
      val count = min(decodeRemaining, compositionRemaining)
      val duration = boundedMultiply(count, decode[decodeIndex].value)
      val startPts = boundedAdd(dts, composition[compositionIndex].value)
      val endPts = boundedAdd(startPts, duration)
      require(startPts in -MAX_EXACT_TIME..MAX_EXACT_TIME && endPts in -MAX_EXACT_TIME..MAX_EXACT_TIME)
      // Each intersection has one duration and composition offset; its presentation intervals are contiguous.
      val clippedStart = max(startPts.toDouble(), lower)
      val clippedEnd = min(endPts.toDouble(), upper)
      if (clippedEnd > clippedStart) {
        val shift = if (mediaEdit != null) emptyDuration - lower / media.scale else 0.0
        first = min(first, clippedStart / media.scale + shift)
        end = max(end, clippedEnd / media.scale + shift)
      }
      dts = boundedAdd(dts, duration)
      decodeRemaining -= count
      compositionRemaining -= count
      if (decodeRemaining == 0L && ++decodeIndex < decode.size) decodeRemaining = decode[decodeIndex].count
      if (compositionRemaining == 0L && ++compositionIndex < composition.size)
        compositionRemaining = composition[compositionIndex].count
    }
    require(first.isFinite() && end.isFinite() && end > first)
    MediaFact.Known(IsoTrackTimeline(first, end, movie.duration.toDouble() / movie.scale,
      1.0 / movie.scale, edits != null))
  } catch (_: Exception) { MediaFact.Unknown }

  private fun timebase(box: IsoMetadataBox): Timebase {
    require(box.end - box.payload >= 4)
    input.seek(box.payload)
    val version = input.readInt()
    require(version == 0 || version == 0x01000000)
    val offset = if (version == 0) 12 else 20
    val length = if (version == 0) 8 else 12
    require(box.end - box.payload >= offset + length)
    input.seek(box.payload + offset)
    val scale = uint()
    val duration = if (version == 0) uint() else input.readLong()
    require(scale > 0 && duration in 1..MAX_EXACT_TIME && (version != 0 || duration != 0xffffffffL))
    return Timebase(scale, duration)
  }

  private fun runs(box: IsoMetadataBox, composition: Boolean): List<Run> {
    require(box.end - box.payload >= 8)
    input.seek(box.payload)
    val version = input.readInt()
    require(version == 0 || (composition && version == 0x01000000))
    val count = uint()
    require(count in 1..remainingRows.toLong() && box.end - box.payload == 8 + count * 8)
    remainingRows -= count.toInt()
    return List(count.toInt()) {
      val samples = uint()
      val value = if (version == 0x01000000) input.readInt().toLong() else uint()
      require(samples > 0 && (composition || value > 0))
      Run(samples, value)
    }
  }

  private fun sampleCount(tables: List<IsoMetadataBox>): Long {
    val box = tables.single { it.type == "stsz" || it.type == "stz2" }
    require(box.end - box.payload >= 12)
    input.seek(box.payload)
    require(input.readInt() == 0)
    val size = uint()
    val count = uint()
    require(count > 0)
    if (box.type == "stsz") {
      require(box.end - box.payload == if (size > 0) 12L else 12 + count * 4)
    } else {
      val fieldSize = size and 255
      require(size == fieldSize && fieldSize in setOf(4L, 8L, 16L))
      require(box.end - box.payload == 12 + (count * fieldSize + 7) / 8)
    }
    return count
  }

  private fun edits(box: IsoMetadataBox): List<Edit> {
    require(box.end - box.payload >= 8)
    input.seek(box.payload)
    val version = input.readInt()
    require(version == 0 || version == 0x01000000)
    val count = uint()
    val rowBytes = if (version == 0) 12 else 20
    require(count in 1..2 && box.end - box.payload == 8 + count * rowBytes)
    val edits = List(count.toInt()) {
      val duration = if (version == 0) uint() else input.readLong()
      val mediaTime = if (version == 0) input.readInt().toLong() else input.readLong()
      require(duration in 1..MAX_EXACT_TIME && mediaTime in -1..MAX_EXACT_TIME)
      require(input.readInt() == 65536) // Unit media rate only; dwell/repeated/rate-changing edits stay unknown.
      Edit(duration, mediaTime)
    }
    require(edits.last().mediaTime >= 0 && (edits.size == 1 || edits.first().mediaTime == -1L))
    return edits
  }

  // Explicit bounds also retain exact integer-to-double conversion on API 23.
  private fun boundedAdd(a: Long, b: Long): Long {
    require(a in -MAX_EXACT_TIME..MAX_EXACT_TIME && b in -MAX_EXACT_TIME..MAX_EXACT_TIME)
    val sum = a + b
    require(sum in -MAX_EXACT_TIME..MAX_EXACT_TIME)
    return sum
  }

  private fun boundedMultiply(a: Long, b: Long): Long {
    require(a in 0..MAX_EXACT_TIME && b in 0..MAX_EXACT_TIME)
    require(b == 0L || a <= MAX_EXACT_TIME / b)
    return a * b
  }

  private fun uint(): Long = input.readInt().toLong() and 0xffffffffL

  companion object {
    private const val MAX_TIMING_ROWS = 65536
    private const val MAX_EXACT_TIME = 1L shl 53
  }
}
