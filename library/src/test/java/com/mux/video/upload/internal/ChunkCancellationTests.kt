package com.mux.video.upload.internal

import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSource
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChunkCancellationTests : AbsRobolectricTest() {
  @After fun tearDown() { unmockkAll() }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test fun cancelledRequestIsNotRetriedAndLateResponseIsClosed() = runTest {
    val callback = slot<Callback>()
    val request = slot<Request>()
    val call = mockk<Call> {
      every { enqueue(capture(callback)) } just Runs
      every { cancel() } just Runs
    }
    val client = mockk<OkHttpClient> { every { newCall(capture(request)) } returns call }
    mockkObject(MuxUploadSdk)
    every { MuxUploadSdk.httpClient() } returns client
    every { MuxUploadSdk.logger } returns MuxUploadSdk.noLogger()
    val info = UploadInfo(remoteUri = Uri.parse("https://example.invalid/upload"), inputFile = File("unused"),
      chunkSize = 4, retriesPerChunk = 3, optOut = true, uploadJob = null, statusFlow = null)
    val progress = MutableSharedFlow<MuxUpload.Progress>(replay = 1)
    val worker = ChunkWorker.create(ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(1, 2, 3, 4)), info, "video/*", progress)
    val job = async { worker.upload() }
    testScheduler.runCurrent()
    assertEquals("bytes 4-7/12", request.captured.header("Content-Range"))
    val buffer = Buffer()
    request.captured.body!!.writeTo(buffer)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer.readByteArray())
    job.cancel()
    testScheduler.runCurrent()
    verify(exactly = 1) { call.cancel() }
    var closed = false
    val body = object : ResponseBody() {
      override fun contentType() = "video/*".toMediaType()
      override fun contentLength() = 0L
      override fun source(): BufferedSource = Buffer()
      override fun close() { closed = true }
    }
    callback.captured.onResponse(call, Response.Builder().request(request.captured)
      .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
    testScheduler.runCurrent()
    assertTrue(job.isCancelled)
    assertTrue(closed)
    assertTrue(progress.replayCache.isEmpty())
    verify(exactly = 1) { client.newCall(any()) }
  }
}
