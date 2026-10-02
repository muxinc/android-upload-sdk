package com.mux.video.upload.internal.standardization

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal data class IsoMetadataBox(val type: String, val payload: Long, val end: Long)

internal object IsoSampleEntries {
  val avc = setOf("avc1", "avc3", "dva1", "dvav")
  val hevc = setOf("hvc1", "hev1", "dvh1", "dvhe")
  val dolby = setOf("dvh1", "dvhe", "dva1", "dvav")
  val visual = avc + hevc + setOf("mp4v", "s263", "vp08", "vp09", "av01", "apco", "apcs", "apcn", "apch", "ap4h", "ap4x")
  fun codec(entry: String): VideoCodec? = when (entry) {
    in avc -> VideoCodec.H264
    in hevc -> VideoCodec.Hevc
    else -> null
  }
}

internal data class IsoTrackMetadata(
  val handler: String?,
  val trackId: MediaFact<Int>,
  val sampleEntries: List<String>,
  val hasDolbyVisionConfiguration: Boolean,
  val rotationDegrees: MediaFact<Int>,
  val hasSimpleSampleGeometry: Boolean,
  val pixelAspectRatio: MediaFact<Double> = MediaFact.Unknown,
  val sampleDimensions: MediaFact<Dimensions> = MediaFact.Unknown,
  val timeline: MediaFact<IsoTrackTimeline> = MediaFact.Unknown,
) {
  val hasSimpleOrientation: Boolean get() = rotationDegrees != MediaFact.Unknown
  fun matches(kind: TrackKind): Boolean = (handler == "vide" && kind == TrackKind.Video) ||
    (handler == "soun" && kind == TrackKind.Audio)
}

/** Seeks past media payloads; reads bounded track, geometry and presentation timing metadata. */
internal object IsoContainerMetadataReader {
  private const val MAX_BOXES = 4096
  private const val MAX_TOP_LEVEL_BOXES = 131072
  private const val MAX_ENTRIES = 16

  fun read(file: File): MediaFact<List<IsoTrackMetadata>> = try {
    RandomAccessFile(file, "r").use { input ->
      val reader = Reader(input)
      var movie: IsoMetadataBox? = null
      var fragmented = false
      for (box in reader.boxes(0, input.length(), topLevel = true)) {
        if (box.type == "moov") { require(movie == null); movie = box }
        if (box.type == "moof") fragmented = true
      }
      val moov = movie ?: return MediaFact.Unknown
      val children = reader.boxes(moov.payload, moov.end).toList()
      val movieHeader = children.singleOrNull { it.type == "mvhd" }
      val tracks = children.filter { it.type == "trak" }
      if (tracks.size !in 1..64) return MediaFact.Unknown
      val metadata = tracks.map { reader.track(it, movieHeader, fragmented) }
      val ids = metadata.mapNotNull { it.trackId.valueOrNull }
      require(ids.distinct().size == ids.size)
      MediaFact.Known(metadata)
    }
  } catch (_: Exception) { MediaFact.Unknown }

  private class Reader(val input: RandomAccessFile) {
    var boxCount = 0
    val timelines = IsoTimelineMetadataReader(input)
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

    fun track(track: IsoMetadataBox, movieHeader: IsoMetadataBox?, fragmented: Boolean): IsoTrackMetadata {
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
      var simpleGeometry = true
      var aspect: MediaFact<Double> = MediaFact.Unknown
      var sampleSize: MediaFact<Dimensions> = MediaFact.Unknown
      if (handler == "vide") {
        for (entry in entries) {
          if (entry.type in IsoSampleEntries.dolby) dolby = true
          if (entry.type in IsoSampleEntries.visual) {
            require(entry.end - entry.payload >= 78)
            input.seek(entry.payload + 24)
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            if (width > 0 && height > 0) sampleSize = MediaFact.Known(Dimensions(width, height))
            val extensions = boxes(entry.payload + 78, entry.end, allowQuickTimeTerminator = true).toList()
            if (extensions.any { it.type in setOf("dvcC", "dvvC", "dvwC") }) dolby = true
            if (extensions.any { it.type == "clap" }) simpleGeometry = false
            val aspects = extensions.filter { it.type == "pasp" }
            if (aspects.size > 1) simpleGeometry = false
            aspect = aspects.singleOrNull()?.let {
              require(it.end - it.payload == 8L)
              input.seek(it.payload)
              val horizontal = input.readInt().toLong() and 0xffffffffL
              val vertical = input.readInt().toLong() and 0xffffffffL
              if (horizontal == 0L || vertical == 0L) { simpleGeometry = false; MediaFact.Unknown }
              else MediaFact.Known(horizontal.toDouble() / vertical)
            } ?: MediaFact.Unknown
            // A declared presentation size can establish aspect even for codecs without an SPS reader.
            if (aspects.isEmpty() && IsoSampleEntries.codec(entry.type) == null) aspect = trackAspect(tkhd, sampleSize)
          } else simpleGeometry = false
        }
      }
      val timeline = if (fragmented || entries.size != 1) MediaFact.Unknown else try {
        val edits = children.filter { it.type == "edts" }
        require(edits.size <= 1)
        val editChildren = edits.singleOrNull()?.let { boxes(it.payload, it.end).toList() }
        timelines.read(movieHeader, media.singleOrNull { it.type == "mdhd" }, editChildren, tables)
      } catch (_: Exception) { MediaFact.Unknown }
      return IsoTrackMetadata(handler, trackId, entries.map { it.type }, dolby, orientation, simpleGeometry,
        if (entries.size == 1 && simpleGeometry) aspect else MediaFact.Unknown,
        if (entries.size == 1) sampleSize else MediaFact.Unknown, timeline)
    }

    private fun trackAspect(tkhd: IsoMetadataBox?, sampleSize: MediaFact<Dimensions>): MediaFact<Double> {
      val size = sampleSize.valueOrNull ?: return MediaFact.Unknown
      val header = tkhd ?: return MediaFact.Unknown
      input.seek(header.payload)
      val offset = when (input.readUnsignedByte()) { 0 -> 76; 1 -> 88; else -> return MediaFact.Unknown }
      if (header.end - header.payload < offset + 8) return MediaFact.Unknown
      input.seek(header.payload + offset)
      val width = (input.readInt().toLong() and 0xffffffffL) / 65536.0
      val height = (input.readInt().toLong() and 0xffffffffL) / 65536.0
      return if (width > 0 && height > 0) positive(width / height * size.height / size.width) else MediaFact.Unknown
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
