package com.mux.video.upload.internal.network

import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.internal.log
import io.mockk.*
import junit.framework.Assert.assertEquals
import okio.Buffer
import okio.BufferedSink
import org.junit.Test

class CountingRequestBodyTests : AbsRobolectricTest() {

  companion object {
    // Should match the READ_LENGTH in CountingRequestBody
    const val READ_LENGTH: Long = 256 * 1024
  }

  @Test
  fun testCallbackIsCalled() {
    val dummyDataLen: Long = READ_LENGTH + (READ_LENGTH / 2) // One full and one partial read
    val dummyData = ByteArray(dummyDataLen.toInt())
    val mockSink = mockk<BufferedSink> {
      every { buffer } returns Buffer()
      @Suppress("DEPRECATION")
      every { buffer() } returns Buffer()
      every { flush() } just runs
      every { close() } just runs
    }
    var reportedReadBytes = 0L
    val mockReadCallback: (Long) -> Unit = { reportedReadBytes = it }

    val body = dummyData.asCountingRequestBody(
      mediaType = mockk(relaxed = true),
      contentLength = dummyDataLen,
      mockReadCallback
    )
    body.writeTo(mockSink)

    assertEquals(
      "The number of read bytes should be properly reported",
      dummyDataLen,
      reportedReadBytes
    )
  }

  @Test
  fun testDataIntegrity() {
    // Let's try to write() the alphabet
    val dummyData = ByteArray(26)
    for (i in dummyData.indices) {
      dummyData[i] = (i + 0x41).toByte() // A, B, C...Y, Z
    }
    // Mock a Buffer to capture data
    val writtenDataSlot = slot<ByteArray>()
    val writeLenSlot = slot<Int>()
    val writtenData = mutableListOf<ByteArray>()
    val mockBuffer = mockk<Buffer> {
      every { write(capture(writtenDataSlot), any(), capture(writeLenSlot)) } answers {
        val writeBuf = writtenDataSlot.captured
        val writeLen = writeLenSlot.captured
        writtenData.add(writeBuf.copyOf(writeLen))
        Buffer()
      }
    }
    // Mock a sink for writing
    val mockSink = mockk<BufferedSink> {
      every { buffer } returns mockBuffer
      @Suppress("DEPRECATION")
      every { buffer() } returns mockBuffer
      every { flush() } just runs
      every { close() } just runs
    }

    val mockReadCallback: (Long) -> Unit = { }
    val body = dummyData.asCountingRequestBody(
      mediaType = mockk(relaxed = true),
      contentLength = 26,
      readSize = 20, // Read such that there's a smaller last piece. This is also a case to test
      mockReadCallback
    )
    body.writeTo(mockSink)

    // You should leave this section. Seeing the buffers stacked vertically is handy for debugging
    log(tag = "testDataIntegrity", message = "Dumping all data")
    writtenData
      .map { String(it, Charsets.US_ASCII) }
      .forEach { log(tag="testDataIntegrity", message= it) }

    val allWittenData = writtenData
      .map { String(it, Charsets.US_ASCII) }
      .reduce { acc, str -> acc + str }

    assertEquals(
      "If you write in the alphabet you should be able to decode it out of written data",
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      allWittenData
    )
  }
}
