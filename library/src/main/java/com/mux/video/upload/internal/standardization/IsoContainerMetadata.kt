package com.mux.video.upload.internal.standardization

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal data class IsoMetadataBox(val type: String, val payload: Long, val end: Long)

internal data class IsoTrackMetadata(
  val handler: String?,
  val trackId: MediaFact<Int>,
  val editList: MediaFact<EditList>,
)

/** Edit-list inspection for sample/timeline analysis only; metadata uses MediaFormat directly. */
internal object IsoContainerMetadataReader {
  private const val MAX_BOXES = 4096
  private const val MAX_TOP_LEVEL_BOXES = 131072

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

  fun videoEdit(file: File, trackId: Int?): MediaFact<EditList> {
    if (trackId == null) return MediaFact.Unknown
    return read(file).valueOrNull?.singleOrNull {
      it.handler == "vide" && it.trackId.valueOrNull == trackId
    }?.editList ?: MediaFact.Unknown
  }

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
      val handler = boxes(mdia.payload, mdia.end).singleOrNull { it.type == "hdlr" }?.let {
        require(it.end - it.payload >= 12)
        input.seek(it.payload + 8)
        ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
      }
      val id = tkhd?.let {
        input.seek(it.payload)
        val offset = when (input.readUnsignedByte()) { 0 -> 12; 1 -> 20; else -> return@let MediaFact.Unknown }
        require(it.end - it.payload >= offset + 4)
        input.seek(it.payload + offset)
        positiveCode(input.readInt())
      } ?: MediaFact.Unknown
      return IsoTrackMetadata(handler, id, edits(children))
    }

    /** Classifies edit structure only; applying it to the presentation timeline is separate work. */
    private fun edits(children: List<IsoMetadataBox>): MediaFact<EditList> {
      val metadataBoxCount = boxCount
      return try { classifyEdits(children) } catch (_: Exception) {
        // A bad edit list must not discard track identity, orientation or Dolby signaling.
        boxCount = metadataBoxCount
        MediaFact.Unknown
      }
    }

    private fun classifyEdits(children: List<IsoMetadataBox>): MediaFact<EditList> {
      val editBoxes = children.filter { it.type == "edts" }
      if (editBoxes.isEmpty()) return MediaFact.Known(EditList.None)
      if (editBoxes.size != 1) return MediaFact.Unknown
      val lists = boxes(editBoxes.single().payload, editBoxes.single().end).toList()
      if (lists.size != 1 || lists.single().type != "elst") return MediaFact.Unknown
      val list = lists.single()
      require(list.end - list.payload >= 8)
      input.seek(list.payload)
      val version = input.readUnsignedByte()
      if (version !in 0..1 || input.readUnsignedByte() != 0 || input.readUnsignedShort() != 0)
        return MediaFact.Unknown
      val count = input.readInt().toLong() and 0xffffffffL
      val entrySize = if (version == 0) 12 else 20
      require(count <= 64 && list.end - list.payload == 8 + count * entrySize)
      if (count == 0L) return MediaFact.Known(EditList.None)
      var classification = if (count == 1L) EditList.None else EditList.Complex
      repeat(count.toInt()) {
        val duration = if (version == 0) input.readInt().toLong() and 0xffffffffL else input.readLong()
        val mediaTime = if (version == 0) input.readInt().toLong() else input.readLong()
        val rate = input.readInt()
        if (duration <= 0) return MediaFact.Unknown
        if (mediaTime < 0 || rate != 0x00010000) classification = EditList.Complex
        else if (count == 1L && mediaTime > 0) classification = EditList.Simple
      }
      return MediaFact.Known(classification)
    }

  }
}
