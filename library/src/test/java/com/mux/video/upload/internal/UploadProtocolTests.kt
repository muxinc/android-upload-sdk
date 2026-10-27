package com.mux.video.upload.internal

import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.internal.standardization.SdrStandardizedFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

class UploadProtocolTests : AbsRobolectricTest() {
  @After fun tearDown() { MuxUploadSdk.useLogger(MuxUploadSdk.noLogger()) }
  private fun info() = UploadInfo(remoteUri = Uri.parse("https://example.invalid/upload"), inputFile = File("unused"),
    chunkSize = 4, retriesPerChunk = 3, optOut = true, uploadJob = null, statusFlow = null)
  private fun response(code: Int, vararg ranges: String) = Response.Builder()
    .request(Request.Builder().url("https://example.invalid/upload").build())
    .protocol(Protocol.HTTP_1_1).code(code).message("Test").body(ByteArray(0).toResponseBody())
    .apply { ranges.forEach { addHeader("Range", it) } }.build()

  @Test fun rejectsMalformedContradictoryAndDuplicateRanges() {
    for (headers in listOf(arrayOf("bytes=1-2"), arrayOf("bytes=0-12"), arrayOf("bytes=0-11"),
      arrayOf("bytes=0-9223372036854775807"), arrayOf("bytes=0-999999999999999999999"),
      arrayOf("bytes=0--1"), arrayOf("bytes=0-2,4-5"), arrayOf("items=0-2"),
      arrayOf("bytes=0-2", "bytes=0-2"), arrayOf(""))) {
      response(308, *headers).use { assertThrows(UploadResumeBlockedException::class.java) {
        UploadProtocol.acknowledgedOffset(it, 12) } }
    }
    response(308).use { assertEquals(0L, UploadProtocol.acknowledgedOffset(it, 12)) }
    response(308, "bytes=0-2").use { assertEquals(3L, UploadProtocol.acknowledgedOffset(it, 12)) }
  }


  @Test fun originalAndStandardizedChunksSharePartialAndOverlappingAcknowledgements() = runBlocking {
    val owned = SdrStandardizedFile.allocate(RuntimeEnvironment.getApplication().cacheDir, 1)!!
    owned.file.writeText("standardized payload"); assertTrue(owned.recordValidation())
    val state = UploadPreparationState().apply { verified = owned }
    val upload = info().update(attempt = UploadAttempt(state, MuxUpload.Progress()))
    val chunk = ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7))
    try {
      for (upload in listOf(info(), upload)) for ((code, ranges, expected) in listOf(Triple(308, arrayOf("bytes=0-5"), 2L),
        Triple(308, emptyArray<String>(), null),
        Triple(308, arrayOf("bytes=0-8"), 5L), Triple(200, emptyArray(), 8L),
        Triple(204, emptyArray(), null))) {
        var requests = 0
        MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
          requests++
          val body = Buffer(); chain.request().body!!.writeTo(body)
          assertArrayEquals(chunk.sliceData, body.readByteArray())
          response(code, *ranges).newBuilder().request(chain.request()).build()
        }.build())
        val progress = MutableSharedFlow<MuxUpload.Progress>(replay = 1)
        val worker = ChunkWorker.create(chunk, upload, "video/*", progress)
        if (expected == null) {
          try { worker.upload(); fail("Ambiguous chunk $code") } catch (_: UploadResumeBlockedException) {}
          assertTrue(progress.replayCache.isEmpty())
        } else {
          assertEquals(expected, worker.upload().bytesUploaded)
          assertEquals(expected, progress.replayCache.single().bytesUploaded)
        }
        assertEquals(1, requests)
      }
      response(200).use { assertEquals(4L, UploadProtocol.acknowledge(it, chunk.copy(startByte = 8, endByte = 11))) }
    } finally { owned.delete() }
  }
  @Test fun chunkPreservesTransportFailureAndClassifiesRetryableResponses() = runBlocking {
    val offline = java.io.IOException("Offline")
    MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().addInterceptor { throw offline }.build())
    try { ChunkWorker.create(ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7)), info(), "video/*", MutableSharedFlow(replay = 1)).upload(); fail("Expected transport failure") }
    catch (e: java.io.IOException) { assertEquals(offline.javaClass, e.javaClass); assertEquals(offline.message, e.message) }
    for (code in listOf(408, 429, 500, 502, 503, 504)) {
      MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
        response(code).newBuilder().request(chain.request()).build()
      }.build())
      try { ChunkWorker.create(ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7)), info(), "video/*", MutableSharedFlow(replay = 1)).upload(); fail("Expected retryable response $code") }
      catch (_: java.io.IOException) {}
      response(code).use { assertThrows(java.io.IOException::class.java) {
        UploadProtocol.acknowledge(it, ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7))) } }
    }
  }

  @Test fun standardizedWorkerLeavesRetryableResponseRecoveryToItsOwner() = runBlocking {
    val owned = SdrStandardizedFile.allocate(RuntimeEnvironment.getApplication().cacheDir, 1)!!
    owned.file.writeText("standardized payload"); assertTrue(owned.recordValidation())
    val state = UploadPreparationState().apply { verified = owned }
    val upload = info().update(attempt = UploadAttempt(state, MuxUpload.Progress()))
    var requests = 0
    MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
      requests++
      response(503).newBuilder().request(chain.request()).build()
    }.build())
    val flow = MutableSharedFlow<MuxUpload.Progress>(replay = 1)
    try {
      try { ChunkWorker.create(ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7)), upload, "video/*", flow).upload()
        fail("Expected retryable chunk failure") }
      catch (_: java.io.IOException) {}
      assertEquals(1, requests); assertTrue(flow.replayCache.isEmpty())
    } finally { owned.delete() }
  }

  @Test fun standardizedLoggingAndAcknowledgedProgressStayStableWhenOwnershipClears() = runBlocking {
    val logs = mutableListOf<String>()
    MuxUploadSdk.useLogger(object : MuxUploadSdk.Logger by MuxUploadSdk.noLogger() {
      override fun v(tag: String, msg: String, e: Exception?) { logs += msg }
    })
    val owned = SdrStandardizedFile.allocate(RuntimeEnvironment.getApplication().cacheDir, 1)!!
    owned.file.writeText("standardized payload"); assertTrue(owned.recordValidation())
    val state = UploadPreparationState().apply { verified = owned }
    val upload = info().update(attempt = UploadAttempt(state, MuxUpload.Progress()))
    val chunk = ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7))
    MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
      state.deleteOwnedFile()
      val body = Buffer(); chain.request().body!!.writeTo(body)
      response(308, "bytes=0-5").newBuilder().request(chain.request()).build()
    }.build())
    val flow = MutableSharedFlow<MuxUpload.Progress>(replay = 1)
    try {
      assertEquals(2L, ChunkWorker.create(chunk, upload, "video/*", flow).upload().bytesUploaded)
      assertEquals(listOf(2L), flow.replayCache.map { it.bytesUploaded })
      assertTrue("Standardized logs: $logs", logs.isEmpty())
    } finally { owned.delete() }
  }

  @Test fun standardizedRequestsNeverReplayAutomaticallyOnRetryableHttpResponses() = runBlocking {
    val owned = SdrStandardizedFile.allocate(RuntimeEnvironment.getApplication().cacheDir, 1)!!
    owned.file.writeText("standardized payload"); assertTrue(owned.recordValidation())
    val state = UploadPreparationState().apply { verified = owned }
    try {
      for (code in listOf(408, 503)) {
        val server = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val serverFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread = Thread {
          try {
            while (!server.isClosed) server.accept().use { socket ->
              val input = socket.getInputStream().bufferedReader()
              check(input.readLine().startsWith("PUT "))
              var length = 0
              while (true) {
                val header = input.readLine()
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
              }
              repeat(length) { check(input.read() >= 0) }
              requests.incrementAndGet()
              socket.getOutputStream().apply {
                write("HTTP/1.1 $code Test\r\nContent-Length: 0\r\nRetry-After: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
              }
            }
          } catch (e: Throwable) { if (!server.isClosed) serverFailure.set(e) }
        }.apply { isDaemon = true; start() }
        val upload = info().copy(remoteUri = Uri.parse("http://upload.test.invalid:${server.localPort}/upload"),
          attempt = UploadAttempt(state, MuxUpload.Progress()))
        MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(object : Dns {
          override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.2"), java.net.InetAddress.getByName("127.0.0.1"))
        }).connectTimeout(100, java.util.concurrent.TimeUnit.MILLISECONDS)
          .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build())
        var failure: java.io.IOException? = null
        try {
          try {
            ChunkWorker.create(ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7)),
              upload, "video/*", MutableSharedFlow(replay = 1)).upload()
            fail("Expected retryable $code")
          } catch (e: java.io.IOException) { failure = e }
        } finally { server.close(); thread.join(1000) }
        assertNull(serverFailure.get())
        assertEquals("$code: $failure", 1, requests.get())
      }
    } finally { owned.delete() }
  }

  @Test fun chunkAcknowledgementsDoNotDependOnPreparationOwnership() = runBlocking {
    val owned = SdrStandardizedFile.allocate(RuntimeEnvironment.getApplication().cacheDir, 1)!!
    owned.file.writeText("standardized payload"); assertTrue(owned.recordValidation())
    val state = UploadPreparationState().apply { verified = owned }
    val attempt = UploadAttempt(state, MuxUpload.Progress())
    attempt.beginTransport(true) {}
    state.deleteOwnedFile()
    val upload = info().update(attempt = attempt)
    MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
      assertTrue(chain.request().body!!.isOneShot())
      response(308, "bytes=0-5").newBuilder().request(chain.request()).build()
    }.build())
    val flow = MutableSharedFlow<MuxUpload.Progress>(replay = 1)
    assertEquals(2L, ChunkWorker.create(ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7)),
      upload, "video/*", flow).upload().bytesUploaded)
    assertEquals(listOf(2L), flow.replayCache.map { it.bytesUploaded })
  }

  @Test fun zeroProgressAcknowledgementsAreRetryableWhileRegressingOffsetsStillBlock() {
    val chunk = ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7))
    response(308, "bytes=0-3").use { assertThrows(UploadRetryException::class.java) {
      UploadProtocol.acknowledge(it, chunk) } }
    response(308).use { assertThrows(UploadRetryException::class.java) {
      UploadProtocol.acknowledge(it, chunk.copy(startByte = 0, endByte = 3)) } }
    response(308, "bytes=0-2").use { assertThrows(UploadResumeBlockedException::class.java) {
      UploadProtocol.acknowledge(it, chunk) } }
  }

  @Test fun retryAfterCarriesBoundedDelayForSecondsAndHttpDates() {
    val chunk = ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7))
    val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
      timeZone = java.util.TimeZone.getTimeZone("GMT")
    }
    val future = format.format(java.util.Date(System.currentTimeMillis() + 30_000))
    for ((header, low, high) in listOf(Triple("30", 30_000L, 30_000L), Triple("0", 0L, 0L),
      Triple("999999999999999999999999", 60_000L, 60_000L), Triple("-1", 0L, 0L),
      Triple("invalid", 0L, 0L), Triple(future, 28_000L, 30_000L),
      Triple("Mon, 01 Jan 2001 00:00:00 GMT", 0L, 0L))) {
      response(503).newBuilder().header("Retry-After", header).build().use {
        val error = assertThrows(UploadRetryException::class.java) { UploadProtocol.acknowledge(it, chunk) }
        assertTrue("$header: ${error.retryAfterMs}", error.retryAfterMs in low..high)
      }
    }
  }

}
