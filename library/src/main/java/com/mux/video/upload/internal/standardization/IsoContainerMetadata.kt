package com.mux.video.upload.internal.standardization

import java.io.File
import java.io.RandomAccessFile

internal data class IsoTrackMetadata(
  val handler: String?,
  val trackId: MediaFact<Int>,
  val sampleEntries: List<String>,
  val hasDolbyVisionConfiguration: Boolean,
  val rotationDegrees: MediaFact<Int>,
  val hasSimpleSampleGeometry: Boolean,
) {
  val hasSimpleOrientation: Boolean get() = rotationDegrees != MediaFact.Unknown
}

/** Seeks past sample payloads/tables; reads only bounded track and sample-description metadata. */
internal object IsoContainerMetadataReader {
  private const val MAX_BOXES = 4096
  private const val MAX_ENTRIES = 16
  private data class Box(val type: String, val payload: Long, val end: Long)

  fun read(file: File): MediaFact<List<IsoTrackMetadata>> = try {
    RandomAccessFile(file, "r").use { input ->
      val reader = Reader(input)
      val moov = reader.boxes(0, input.length()).singleOrNull { it.type == "moov" }
        ?: return MediaFact.Unknown
      val tracks = reader.boxes(moov.payload, moov.end).filter { it.type == "trak" }
      if (tracks.size !in 1..64) return MediaFact.Unknown
      val metadata = tracks.map { reader.track(it) }
      val ids = metadata.mapNotNull { it.trackId.valueOrNull }
      require(ids.distinct().size == ids.size)
      MediaFact.Known(metadata)
    }
  } catch (_: Exception) { MediaFact.Unknown }

  private class Reader(val input: RandomAccessFile) {
    var boxCount = 0
    fun boxes(start: Long, end: Long, allowQuickTimeTerminator: Boolean = false): List<Box> {
      require(start <= end && end <= input.length())
      val result = mutableListOf<Box>()
      var position = start
      while (position < end) {
        if (allowQuickTimeTerminator && end - position == 4L) {
          input.seek(position)
          require(input.readInt() == 0)
          break
        }
        require(end - position >= 8 && ++boxCount <= MAX_BOXES)
        input.seek(position)
        val size32 = input.readInt().toLong() and 0xffffffffL
        val type = fourCc()
        val header = if (size32 == 1L) 16 else 8
        val size = when (size32) { 0L -> end - position; 1L -> input.readLong(); else -> size32 }
        require(size >= header && size <= end - position)
        result.add(Box(type, position + header, position + size))
        position += size
      }
      return result
    }

    fun track(track: Box): IsoTrackMetadata {
      val children = boxes(track.payload, track.end)
      val tkhd = children.singleOrNull { it.type == "tkhd" }
      val mdia = children.single { it.type == "mdia" }
      val media = boxes(mdia.payload, mdia.end)
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
      val stsd = boxes(stbl.payload, stbl.end).single { it.type == "stsd" }
      require(stsd.end - stsd.payload >= 8)
      input.seek(stsd.payload + 4)
      val count = input.readInt()
      require(count in 1..MAX_ENTRIES)
      val entries = boxes(stsd.payload + 8, stsd.end)
      require(entries.size == count)
      var dolby = false
      var simpleGeometry = true
      if (handler == "vide") {
        for (entry in entries) {
          if (entry.type in listOf("dvhe", "dvh1", "dvav", "dva1")) dolby = true
          if (entry.type in listOf("hvc1", "hev1", "avc1", "avc3", "dvhe", "dvh1", "dvav", "dva1")) {
            require(entry.end - entry.payload >= 78)
            val extensions = boxes(entry.payload + 78, entry.end, allowQuickTimeTerminator = true)
            if (extensions.any { it.type in listOf("dvcC", "dvvC", "dvwC") }) dolby = true
            if (extensions.any { it.type == "clap" }) simpleGeometry = false
            val aspects = extensions.filter { it.type == "pasp" }
            if (aspects.size > 1) simpleGeometry = false
            for (aspect in aspects) {
              require(aspect.end - aspect.payload == 8L)
              input.seek(aspect.payload)
              val horizontal = input.readInt().toLong() and 0xffffffffL
              val vertical = input.readInt().toLong() and 0xffffffffL
              // Retain uncertain anamorphic/clean-aperture geometry for a later richer reader.
              if (horizontal == 0L || horizontal != vertical) simpleGeometry = false
            }
          }
        }
      }
      return IsoTrackMetadata(handler, trackId, entries.map { it.type }, dolby, orientation, simpleGeometry)
    }

    private fun orientation(box: Box): MediaFact<Int> {
      input.seek(box.payload)
      val version = input.readUnsignedByte()
      val offset = when (version) { 0 -> 40; 1 -> 52; else -> return MediaFact.Unknown }
      require(box.end - box.payload >= offset + 36)
      input.seek(box.payload + offset)
      val matrix = IntArray(9) { input.readInt() }
      // Unit rotations plus translation only. Scale, shear, reflection and perspective stay unproven.
      if (matrix[2] != 0 || matrix[5] != 0 || matrix[8] != 0x40000000) return MediaFact.Unknown
      val axes = listOf(matrix[0], matrix[1], matrix[3], matrix[4])
      // Normalize the container transform to the shared clockwise convention.
      return when (axes) {
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
