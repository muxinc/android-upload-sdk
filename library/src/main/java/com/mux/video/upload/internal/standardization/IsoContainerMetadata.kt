package com.mux.video.upload.internal.standardization

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal data class IsoMetadataBox(val type: String, val payload: Long, val end: Long)

internal object IsoSampleEntries {
  val avc = setOf("avc1", "avc3", "dva1", "dvav")
  val hevc = setOf("hvc1", "hev1", "dvh1", "dvhe")
  val dolby = setOf("dvh1", "dvhe", "dva1", "dvav")
  val visual = avc + hevc
}

internal data class IsoTrackMetadata(
  val handler: String?,
  val trackId: MediaFact<Int>,
  val sampleEntries: List<String>,
  val hasDolbyVisionConfiguration: Boolean,
  val rotationDegrees: MediaFact<Int>,
  val hasSimpleSampleGeometry: Boolean,
) {
  val hasSimpleOrientation: Boolean get() = rotationDegrees != MediaFact.Unknown
  fun matches(kind: TrackKind): Boolean = (handler == "vide" && kind == TrackKind.Video) ||
    (handler == "soun" && kind == TrackKind.Audio)
}

/** Seeks past media payloads; reads track identity, simple orientation and Dolby Vision signaling. */
internal object IsoContainerMetadataReader {
  private const val MAX_BOXES = 4096
  private const val MAX_TOP_LEVEL_BOXES = 131072
  private const val MAX_ENTRIES = 16

  fun read(file: File): MediaFact<List<IsoTrackMetadata>> = try {
    RandomAccessFile(file, "r").use { input ->
      val reader = Reader(input)
      var movie: IsoMetadataBox? = null
      for (box in reader.boxes(0, input.length(), topLevel = true)) {
        if (box.type == "moov") { require(movie == null); movie = box }
      }
      val moov = movie ?: return MediaFact.Unknown
      val children = reader.boxes(moov.payload, moov.end).toList()
      val tracks = children.filter { it.type == "trak" }
      if (tracks.size !in 1..64) return MediaFact.Unknown
      val metadata = tracks.map { reader.track(it) }
      val ids = metadata.mapNotNull { it.trackId.valueOrNull }
      require(ids.distinct().size == ids.size)
      MediaFact.Known(metadata)
    }
  } catch (_: Exception) { MediaFact.Unknown }

  private class Reader(val input: RandomAccessFile) {
    var boxCount = 0
    val boxHeader = ByteArray(16)

    fun boxes(start: Long, end: Long, allowQuickTimeTerminator: Boolean = false,
      topLevel: Boolean = false): Sequence<IsoMetadataBox> = sequence {
      require(start <= end && end <= input.length())
      var topCount = 0
      var position = start
      while (position < end) {
        if (allowQuickTimeTerminator && end - position == 4L) {
          input.seek(position)
          require(input.readInt() == 0)
          break
        }
        require(end - position >= 8)
        require(if (topLevel) ++topCount <= MAX_TOP_LEVEL_BOXES else ++boxCount <= MAX_BOXES)
        input.seek(position)
        input.readFully(boxHeader, 0, minOf(16L, end - position).toInt())
        val bytes = ByteBuffer.wrap(boxHeader)
        val size32 = bytes.int.toLong() and 0xffffffffL
        val type = String(boxHeader, 4, 4, Charsets.US_ASCII)
        val header = if (size32 == 1L) 16 else 8
        require(end - position >= header)
        val size = when (size32) { 0L -> end - position; 1L -> bytes.getLong(8); else -> size32 }
        require(size >= header && size <= end - position)
        yield(IsoMetadataBox(type, position + header, position + size))
        position += size
      }
    }

    fun track(track: IsoMetadataBox): IsoTrackMetadata {
      val children = boxes(track.payload, track.end).toList()
      val tkhd = children.singleOrNull { it.type == "tkhd" }
      val mdia = children.single { it.type == "mdia" }
      val media = boxes(mdia.payload, mdia.end).toList()
      val handler = media.singleOrNull { it.type == "hdlr" }?.let {
        require(it.end - it.payload >= 12)
        input.seek(it.payload + 8)
        fourCc()
      }
      val trackId = tkhd?.let {
        input.seek(it.payload)
        val version = input.readUnsignedByte()
        val offset = when (version) { 0 -> 12; 1 -> 20; else -> -1 }
        if (offset < 0 || it.end - it.payload < offset + 4) MediaFact.Unknown else {
          input.seek(it.payload + offset)
          positiveCode(input.readInt())
        }
      } ?: MediaFact.Unknown
      val orientation = tkhd?.let(::orientation) ?: MediaFact.Unknown
      if (handler !in listOf("vide", "soun"))
        return IsoTrackMetadata(handler, trackId, emptyList(), false, orientation, false)
      val minf = media.single { it.type == "minf" }
      val stbl = boxes(minf.payload, minf.end).single { it.type == "stbl" }
      val tables = boxes(stbl.payload, stbl.end).toList()
      val stsd = tables.single { it.type == "stsd" }
      require(stsd.end - stsd.payload >= 8)
      input.seek(stsd.payload + 4)
      val count = input.readInt()
      require(count in 1..MAX_ENTRIES)
      val entries = boxes(stsd.payload + 8, stsd.end).toList()
      require(entries.size == count)
      var dolby = false
      var simpleGeometry = entries.size == 1
      if (handler == "vide") {
        for (entry in entries) {
          if (entry.type in IsoSampleEntries.dolby) dolby = true
          if (entry.type in IsoSampleEntries.visual) {
            require(entry.end - entry.payload >= 78)
            val extensions = boxes(entry.payload + 78, entry.end, allowQuickTimeTerminator = true).toList()
            if (extensions.any { it.type in setOf("dvcC", "dvvC", "dvwC") }) dolby = true
            if (extensions.any { it.type == "clap" }) simpleGeometry = false
            val aspects = extensions.filter { it.type == "pasp" }
            if (aspects.size > 1) simpleGeometry = false
            for (aspect in aspects) {
              require(aspect.end - aspect.payload == 8L)
              input.seek(aspect.payload)
              val horizontal = input.readInt()
              val vertical = input.readInt()
              // Only square pixels are supported; unusual geometry keeps original-file fallback.
              if (horizontal == 0 || horizontal != vertical) simpleGeometry = false
            }
          } else simpleGeometry = false
        }
      }
      return IsoTrackMetadata(handler, trackId, entries.map { it.type }, dolby, orientation, simpleGeometry)
    }

    private fun orientation(box: IsoMetadataBox): MediaFact<Int> {
      input.seek(box.payload)
      val version = input.readUnsignedByte()
      val offset = when (version) { 0 -> 40; 1 -> 52; else -> return MediaFact.Unknown }
      require(box.end - box.payload >= offset + 36)
      input.seek(box.payload + offset)
      val matrix = IntArray(9) { input.readInt() }
      if (matrix[2] != 0 || matrix[5] != 0 || matrix[8] != 0x40000000) return MediaFact.Unknown
      // Same normalized transform convention as Swift. Android's reported angle is negated separately.
      return when (listOf(matrix[0], matrix[1], matrix[3], matrix[4])) {
        listOf(65536, 0, 0, 65536) -> MediaFact.Known(0)
        listOf(0, 65536, -65536, 0) -> MediaFact.Known(270)
        listOf(-65536, 0, 0, -65536) -> MediaFact.Known(180)
        listOf(0, -65536, 65536, 0) -> MediaFact.Known(90)
        else -> MediaFact.Unknown
      }
    }

    private fun fourCc(): String {
      val bytes = ByteArray(4)
      input.readFully(bytes)
      return String(bytes, Charsets.US_ASCII)
    }
  }
}
