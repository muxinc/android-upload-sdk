package com.mux.video.upload.internal.network

import com.mux.exoplayeradapter.AbsRobolectricTest
import io.mockk.*
import junit.framework.Assert.assertEquals
import okhttp3.MediaType
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.buffer
import org.junit.Test
import java.io.ByteArrayInputStream

class CountingRequestBodyTests : AbsRobolectricTest() {

  companion object {
    // Should match the READ_LENGTH in CountingRequestBody
    const val READ_LENGTH: Long = 256 * 1024
  }

  @Test
  fun testCallbackIsCalled() {
    val dummyDataLen: Long = READ_LENGTH + (READ_LENGTH / 2) // One full and one partial read
    val dummyData = ByteArrayInputStream(ByteArray(dummyDataLen.toInt()))
    val mockSink = mockk<BufferedSink> {
      every { buffer } returns Buffer()
      @Suppress("DEPRECATION")
      every { buffer() } returns Buffer()
      every { flush() } just runs
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
}