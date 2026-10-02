package com.mux.video.upload.internal.standardization

import android.media.MediaFormat
import com.mux.exoplayeradapter.AbsRobolectricTest
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

@Config(sdk = [23])
class IsoContainerMetadataTests : AbsRobolectricTest() {
  private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
  private fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray() + payload
  private fun track(handler: String = "vide", entry: String = "hvc1", extension: ByteArray = byteArrayOf(),
    matrix: IntArray = intArrayOf(65536, 0, 0, 0, 65536, 0, 0, 0, 0x40000000),
    swappedDisplaySize: Boolean = false): ByteArray {
    val tkhd = ByteArray(84)
    for (i in matrix.indices) int(matrix[i]).copyInto(tkhd, 40 + i * 4)
    val hdlr = ByteArray(8) + handler.toByteArray()
    val description = ByteArray(if (handler == "vide") 78 else 28)
    if (handler == "vide") {
      ByteBuffer.wrap(description).putShort(24, 1920.toShort()).putShort(26, 1080.toShort())
      int((if (swappedDisplaySize) 1080 else 1920) * 65536).copyInto(tkhd, 76)
      int((if (swappedDisplaySize) 1920 else 1080) * 65536).copyInto(tkhd, 80)
    }
    val sampleEntry = box(entry, description + extension)
    val stsd = box("stsd", ByteArray(4) + int(1) + sampleEntry)
    return box("trak", box("tkhd", tkhd) +
      box("mdia", box("hdlr", hdlr) + box("minf", box("stbl", stsd))))
  }

  private fun read(data: ByteArray): MediaFact<List<IsoTrackMetadata>> {
    val file = File.createTempFile("iso-metadata", ".mp4")
    return try { file.writeBytes(data); IsoContainerMetadataReader.read(file) } finally { file.delete() }
  }

  @Test fun readsContainerOrderAndDolbyConfigurationInOrdinaryHevcEntry() {
    val tracks = read(box("ftyp", "isom".toByteArray()) + box("moov",
      track(extension = box("dvvC", byteArrayOf(1, 0, 0, 0))) + track("soun", "mp4a"))).valueOrNull!!
    assertEquals(listOf("vide", "soun"), tracks.map { it.handler })
    assertEquals(listOf("hvc1"), tracks[0].sampleEntries)
    assertTrue(tracks[0].hasDolbyVisionConfiguration)
    assertTrue(tracks[0].hasSimpleOrientation)
  }

  @Test fun readsDolbySampleEntryEvenWithoutConfigurationChild() {
    assertTrue(read(box("moov", track(entry = "dvh1"))).valueOrNull!![0].hasDolbyVisionConfiguration)
  }

  @Test fun doesNotClaimSimpleGeometryForReflectionShearOrScale() {
    for (matrix in listOf(
      intArrayOf(-65536, 0, 0, 0, 65536, 0, 0, 0, 0x40000000),
      intArrayOf(65536, 100, 0, 0, 65536, 0, 0, 0, 0x40000000),
      intArrayOf(131072, 0, 0, 0, 65536, 0, 0, 0, 0x40000000))) {
      assertFalse(read(box("moov", track(matrix = matrix))).valueOrNull!![0].hasSimpleOrientation)
    }
  }

  @Test fun recognizesQuarterRotationWithTranslation() {
    val matrix = intArrayOf(0, 65536, 0, -65536, 0, 0, 1920 * 65536, 0, 0x40000000)
    assertTrue(read(box("moov", track(matrix = matrix))).valueOrNull!![0].hasSimpleOrientation)
  }

  @Test fun cleanApertureStaysUnknownAndExplicitPixelAspectIsRetained() {
    assertFalse(read(box("moov", track(extension = box("clap", ByteArray(32)))))
      .valueOrNull!![0].hasSimpleSampleGeometry)
    assertEquals(MediaFact.Known(2.0), read(box("moov", track(extension = box("pasp", int(2) + int(1)))))
      .valueOrNull!![0].pixelAspectRatio)
    assertTrue(read(box("moov", track(extension = box("pasp", int(1) + int(1)))))
      .valueOrNull!![0].hasSimpleSampleGeometry)
  }

  @Test fun inferredTrackAspectCannotContradictAvcSps() {
    val iso = read(box("moov", track(entry = "avc1", swappedDisplaySize = true))).valueOrNull!!.single()
    assertEquals(MediaFact.Unknown, iso.pixelAspectRatio)
    val sps = "000000016742c028da01e0089f97016a02020280000003008000001e078c1950"
      .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
      setByteBuffer("csd-0", ByteBuffer.wrap(sps))
    }
    val metadata = MediaTrackMetadataReader.read(0, format, 23, iso.rotationDegrees, iso.pixelAspectRatio)
    assertEquals(MediaFact.Known(Dimensions(1920, 1080)), metadata.video!!.displayDimensions)
    val explicit = read(box("moov", track(entry = "avc1", swappedDisplaySize = true,
      extension = box("pasp", int(2) + int(1))))).valueOrNull!!.single()
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(0, format, 23,
      explicit.rotationDegrees, explicit.pixelAspectRatio).video!!.displayDimensions)
  }

  @Test fun malformedSizesAndBoxLimitsStayUnknown() {
    for (data in listOf(byteArrayOf(), int(4096) + "moov".toByteArray(),
      int(4) + "moov".toByteArray(), int(1) + "moov".toByteArray() + ByteArray(8) { -1 })) {
      assertEquals(MediaFact.Unknown, read(data))
    }
    val many = ByteArray(4097 * 8)
    val free = box("free", byteArrayOf())
    for (i in 0..4096) free.copyInto(many, i * 8)
    assertEquals(MediaFact.Unknown, read(many))
  }

  @Test fun permitsOnlyZeroQuickTimeSampleDescriptionTerminator() {
    assertTrue(read(box("moov", track(extension = box("dvvC", ByteArray(4)) + ByteArray(4))))
      .valueOrNull!![0].hasDolbyVisionConfiguration)
    assertEquals(MediaFact.Unknown, read(box("moov", track(extension = int(1)))))
  }

  @Test fun noTrackOrDuplicateMovieBoxDoesNotInventAbsence() {
    assertEquals(MediaFact.Unknown, read(box("moov", byteArrayOf())))
    assertEquals(MediaFact.Unknown, read(box("moov", track()) + box("moov", track())))
  }

  @Test fun everyUnitMatrixHasTheSharedNormalizedDegreeValue() {
    for ((axes, degrees) in listOf(
      listOf(65536, 0, 0, 65536) to 0,
      listOf(0, 65536, -65536, 0) to 270,
      listOf(-65536, 0, 0, -65536) to 180,
      listOf(0, -65536, 65536, 0) to 90)) {
      val matrix = intArrayOf(axes[0], axes[1], 0, axes[2], axes[3], 0, 1920 * 65536, 0, 0x40000000)
      assertEquals(MediaFact.Known(degrees), read(box("moov", track(matrix = matrix))).valueOrNull!![0].rotationDegrees)
    }
  }

  @Test fun fragmentHeadersDoNotConsumeMovieMetadataBudget() {
    val fragment = box("moof", byteArrayOf()) + box("mdat", byteArrayOf())
    val many = ByteArray(7200 * fragment.size)
    repeat(7200) { fragment.copyInto(many, it * fragment.size) }
    val movie = box("moov", track())
    assertNotEquals(MediaFact.Unknown, read(movie + many))
    assertNotEquals(MediaFact.Unknown, read(many + movie))
    assertEquals(MediaFact.Unknown, read(movie + many + movie))
    assertEquals(MediaFact.Unknown, read(movie + byteArrayOf(1)))
  }

  @Test fun quickTimeLeadingBoxesAreRecognizedAsIso() {
    for (type in listOf("skip", "uuid", "pnot")) {
      val file = File.createTempFile("iso-sniff", ".mov")
      try {
        file.writeBytes(box(type, ByteArray(16)) + box("moov", track()))
        assertEquals(MediaFact.Known(ContainerKind.IsoBaseMedia), MediaMetadataInspector().container(file))
      } finally { file.delete() }
    }
  }
}
