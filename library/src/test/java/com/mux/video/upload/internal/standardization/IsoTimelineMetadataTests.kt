package com.mux.video.upload.internal.standardization

import android.media.MediaFormat
import com.mux.exoplayeradapter.AbsRobolectricTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

@Config(sdk = [23])
class IsoTimelineMetadataTests : AbsRobolectricTest() {
  private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
  private fun long(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
  private fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray() + payload
  private fun header(type: String, scale: Int, duration: Long, version: Int = 0): ByteArray = box(type,
    int(version shl 24) + ByteArray(if (version == 0) 8 else 16) + int(scale) +
      (if (version == 0) int(duration.toInt()) else long(duration)))
  private fun runs(type: String, rows: List<Pair<Int, Int>>, version: Int = 0) = box(type,
    int(version shl 24) + int(rows.size) + rows.fold(byteArrayOf()) { bytes, row -> bytes + int(row.first) + int(row.second) })
  private fun edit(rows: List<Triple<Long, Long, Int>>, version: Int = 0) = box("edts", box("elst",
    int(version shl 24) + int(rows.size) + rows.fold(byteArrayOf()) { bytes, (duration, time, rate) ->
      bytes + (if (version == 0) int(duration.toInt()) + int(time.toInt()) else long(duration) + long(time)) + int(rate)
    }))

  private fun track(handler: String = "vide", id: Int = 1, scale: Int = 1000, duration: Long = 3000,
    timing: ByteArray = runs("stts", listOf(3 to 1000)), samples: Int = 3,
    composition: ByteArray = byteArrayOf(), edits: ByteArray = byteArrayOf(), version: Int = 0,
    entry: String = if (handler == "vide") "vp09" else "mp4a", aspect: ByteArray = byteArrayOf()): ByteArray {
    val tkhd = ByteArray(84)
    int(id).copyInto(tkhd, 12)
    val matrix = intArrayOf(65536, 0, 0, 0, 65536, 0, 0, 0, 0x40000000)
    matrix.forEachIndexed { i, n -> int(n).copyInto(tkhd, 40 + i * 4) }
    int(1920 * 65536).copyInto(tkhd, 76); int(1080 * 65536).copyInto(tkhd, 80)
    val description = ByteArray(if (handler == "vide") 78 else 28)
    if (handler == "vide") {
      ByteBuffer.wrap(description).putShort(24, 1920.toShort()).putShort(26, 1080.toShort())
    }
    val stsd = box("stsd", int(0) + int(1) + box(entry, description + aspect))
    val stsz = box("stsz", int(0) + int(1) + int(samples))
    return box("trak", box("tkhd", tkhd) + edits + box("mdia", header("mdhd", scale, duration, version) +
      box("hdlr", ByteArray(8) + handler.toByteArray()) + box("minf", box("stbl", stsd + timing + composition + stsz))))
  }

  private fun read(vararg tracks: ByteArray, duration: Long = 3000, scale: Int = 1000,
    version: Int = 0, suffix: ByteArray = byteArrayOf()): List<IsoTrackMetadata> {
    val file = File.createTempFile("iso-timeline", ".mp4")
    return try {
      file.writeBytes(box("moov", header("mvhd", scale, duration, version) + tracks.fold(byteArrayOf(), ByteArray::plus)) + suffix)
      IsoContainerMetadataReader.read(file).valueOrNull!!
    } finally { file.delete() }
  }

  private fun facts(iso: List<IsoTrackMetadata>, priming: Int = 0): MediaFacts {
    val views = iso.mapIndexed { index, track ->
      val format = if (track.handler == "vide") MediaFormat.createVideoFormat("video/x-vnd.on2.vp9", 1920, 1080)
        .apply { setInteger(MediaFormat.KEY_ROTATION, 0); setInteger("color-transfer", 3) }
        else MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply {
          setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x11, 0x90.toByte())))
          setInteger(MediaFormat.KEY_ENCODER_DELAY, priming)
        }
      MediaTrackMetadataReader.read(index, format, 24, track.rotationDegrees, track.pixelAspectRatio)
        .copy(containerIndex = MediaFact.Known(index))
    }
    return MediaContainerMetadataReader.facts(MediaFact.Known(ContainerKind.IsoBaseMedia), MediaFact.Known(iso), views)
  }

  @Test fun establishesUneditedDurationAndAvStartWithoutSamplePayloads() {
    val iso = read(track(), track("soun", 2))
    val f = facts(iso)
    assertEquals(MediaFact.Known(3.0), f.durationSeconds)
    assertEquals(MediaFact.Known(0.0), f.audioVideoStartOffsetSeconds)
    assertEquals(MediaFact.Unknown, f.timestamps)
    assertEquals(MediaFact.Unknown, f.cadence)
    assertEquals(MediaFact.Unknown, f.frameRate)
    assertEquals(MediaFact.Unknown, f.editList)
  }

  @Test fun supportsVersionOneHeadersAndSignedCompositionOffsets() {
    val video = track(version = 1, composition = runs("ctts", listOf(1 to 100, 1 to -100, 1 to 0), 1))
    val audio = track("soun", 2, version = 1)
    val f = facts(read(video, audio, version = 1))
    assertEquals(MediaFact.Known(3.0), f.durationSeconds)
    assertEquals(-0.1, f.audioVideoStartOffsetSeconds.valueOrNull!!, 1e-9)
  }

  @Test fun timingRunBoundariesAreMergedWithoutExpandingSamples() {
    val t = track(timing = runs("stts", listOf(1 to 1000, 2 to 1000)),
      composition = runs("ctts", listOf(2 to 0, 1 to 0)))
    assertEquals(MediaFact.Known(3.0), facts(read(t)).durationSeconds)
  }

  @Test fun unitRateTrimMapsCompositionOffsetsIntoPresentationTime() {
    val edits = edit(listOf(Triple(2000L, 1000L, 65536)))
    val iso = read(track(edits = edits), track("soun", 2, edits = edits), duration = 2000)
    val f = facts(iso, priming = 1024)
    assertEquals(MediaFact.Known(2.0), f.durationSeconds)
    assertEquals(MediaFact.Known(0.0), f.audioVideoStartOffsetSeconds)
  }

  @Test fun leadingEmptyEditCreatesKnownAudioDelay() {
    val delayed = edit(listOf(Triple(100L, -1L, 65536), Triple(2900L, 0L, 65536)), version = 1)
    val f = facts(read(track(), track("soun", 2, edits = delayed)))
    assertEquals(MediaFact.Known(3.0), f.durationSeconds)
    assertEquals(0.1, f.audioVideoStartOffsetSeconds.valueOrNull!!, 1e-9)
  }

  @Test fun editAndMediaTimescalesAreConvertedIndependently() {
    val media = track(scale = 48000, duration = 144000, timing = runs("stts", listOf(3 to 48000)),
      edits = edit(listOf(Triple(2000L, 48000L, 65536))))
    assertEquals(MediaFact.Known(2.0), facts(read(media, duration = 2000)).durationSeconds)
  }

  @Test fun unsupportedEditsDoNotDiscardCodecAndGeometry() {
    for (edits in listOf(
      edit(listOf(Triple(3000L, 0L, 0))),
      edit(listOf(Triple(1500L, 0L, 65536), Triple(1500L, 1000L, 65536))),
      edit(listOf(Triple(3000L, -1L, 65536))))) {
      val f = facts(read(track(edits = edits)))
      assertEquals(MediaFact.Known(VideoCodec.Other), f.videoCodec)
      assertEquals(MediaFact.Known(Dimensions(1920, 1080)), f.displayDimensions)
      assertEquals(MediaFact.Unknown, f.durationSeconds)
    }
  }

  @Test fun malformedOrConflictingTablesKeepOnlyTimelineUnknown() {
    for (t in listOf(
      track(samples = 4), track(duration = 2000),
      track(composition = runs("ctts", listOf(2 to 0))),
      track(timing = runs("stts", listOf(3 to 0))),
      track(timing = box("stts", int(0) + int(65537))),
      track(timing = box("stts", int(0) + int(1) + int(3))),
      track(composition = runs("ctts", listOf(3 to 0), 2)),
      track(duration = 0),
      track(timing = runs("stts", listOf(-1 to -1)), samples = -1))) {
      val f = facts(read(t))
      assertEquals(MediaFact.Known(VideoCodec.Other), f.videoCodec)
      assertEquals(MediaFact.Unknown, f.durationSeconds)
    }
  }

  @Test fun movieDurationConflictAndFragmentedTimelinesStayUnknown() {
    assertEquals(MediaFact.Unknown, facts(read(track(), duration = 2000)).durationSeconds)
    val iso = read(track(), suffix = box("moof", byteArrayOf()) + box("mdat", byteArrayOf()))
    assertEquals(MediaFact.Unknown, iso.single().timeline)
    assertEquals(MediaFact.Known(VideoCodec.Other), facts(iso).videoCodec)
  }

  @Test fun primingWithoutAnEditCannotProveAudioStartOffset() {
    val f = facts(read(track(), track("soun", 2)), priming = 1024)
    assertEquals(MediaFact.Unknown, f.audioVideoStartOffsetSeconds)
    assertEquals(MediaFact.Known(3.0), f.durationSeconds)
  }

  @Test fun otherVisualCodecsUseDeclaredGeometryWithoutCodecHeaderGuessing() {
    for (entry in listOf("vp09", "mp4v", "apch")) {
      val iso = read(track(entry = entry))
      assertEquals(MediaFact.Known(1.0), iso.single().pixelAspectRatio)
      assertEquals(MediaFact.Known(Dimensions(1920, 1080)), facts(iso).displayDimensions)
    }
    val anamorphic = read(track(aspect = box("pasp", int(2) + int(1))))
    assertEquals(MediaFact.Known(Dimensions(3840, 1080)), facts(anamorphic).displayDimensions)
  }
}
