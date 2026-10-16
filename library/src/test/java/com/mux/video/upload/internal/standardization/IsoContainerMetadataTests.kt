package com.mux.video.upload.internal.standardization

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
  private fun track(id: Int = 1, edits: ByteArray = byteArrayOf(), handler: String = "vide") =
    box("trak", box("tkhd", ByteArray(12) + int(id)) + edits +
      box("mdia", box("hdlr", ByteArray(8) + handler.toByteArray())))
  private fun read(data: ByteArray): MediaFact<List<IsoTrackMetadata>> {
    val file = File.createTempFile("iso-edits", ".mp4")
    return try { file.writeBytes(data); IsoContainerMetadataReader.read(file) } finally { file.delete() }
  }

  private fun edit(version: Int = 0, entries: List<Pair<Long, Long>> = listOf(3000L to 0L),
    rate: Int = 65536): ByteArray {
    val payload = byteArrayOf(version.toByte(), 0, 0, 0) + int(entries.size) + entries.fold(byteArrayOf()) { bytes, (duration, time) ->
      bytes + (if (version == 0) int(duration.toInt()) + int(time.toInt())
        else ByteBuffer.allocate(16).putLong(duration).putLong(time).array()) + int(rate)
    }
    return box("edts", box("elst", payload))
  }

  @Test fun classifiesEditsWithoutApplyingTheirTimeline() {
    fun classification(edits: ByteArray) = read(box("moov", track(edits = edits))).valueOrNull!!.single().editList
    assertEquals(MediaFact.Known(EditList.None), classification(byteArrayOf()))
    assertEquals(MediaFact.Known(EditList.None), classification(edit(entries = emptyList())))
    for (version in 0..1) {
      assertEquals(MediaFact.Unknown, classification(edit(version, listOf(0L to 0L))))
      assertEquals(MediaFact.Unknown, classification(edit(version, listOf(3000L to 0L, 0L to 1000L))))
      assertEquals(MediaFact.Known(EditList.None), classification(edit(version, listOf(3000L to 0L))))
      assertEquals(MediaFact.Known(EditList.Simple), classification(edit(version, listOf(3000L to 123L))))
      assertEquals(MediaFact.Known(EditList.Complex), classification(edit(version, listOf(3000L to -1L))))
    }
    assertEquals(MediaFact.Known(EditList.Complex), classification(edit(entries = listOf(3000L to 0L, 1000L to 2000L))))
    assertEquals(MediaFact.Known(EditList.Complex), classification(edit(rate = 0)))
    assertEquals(MediaFact.Unknown, classification(edit(version = 2)))
    assertEquals(MediaFact.Unknown, classification(edit() + edit()))
    assertEquals(MediaFact.Unknown, classification(box("edts", box("elst", ByteArray(5)))))
  }

  @Test fun malformedEditsDoNotDiscardTrackIdentityAndDuplicateIdsStayUnknown() {
    val data = box("moov", track(7, box("edts", box("elst", ByteArray(5)))) + track(8, handler = "soun"))
    val tracks = read(data).valueOrNull!!
    assertEquals(listOf("vide", "soun"), tracks.map { it.handler })
    assertEquals(MediaFact.Known(7), tracks[0].trackId)
    assertEquals(MediaFact.Unknown, tracks[0].editList)
    assertEquals(MediaFact.Known(8), tracks[1].trackId)
    assertEquals(MediaFact.Unknown, read(box("moov", track() + track())))
  }

  @Test fun malformedBoxesAndMissingMoviesRemainUnknown() {
    assertEquals(MediaFact.Unknown, read(byteArrayOf()))
    assertEquals(MediaFact.Unknown, read(int(100) + "moov".toByteArray()))
    assertEquals(MediaFact.Unknown, read(box("moov", track()) + box("moov", track())))
  }
}
