package com.mux.video.upload.internal

import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.internal.standardization.SdrGeneratedFile
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

class GeneratedUploadProtocolTests : AbsRobolectricTest() {
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
      response(308, *headers).use { assertThrows(GeneratedResumeBlockedException::class.java) {
        GeneratedUploadProtocol.acknowledgedOffset(it, 12) } }
    }
    response(308).use { assertEquals(0L, GeneratedUploadProtocol.acknowledgedOffset(it, 12)) }
    response(308, "bytes=0-2").use { assertEquals(3L, GeneratedUploadProtocol.acknowledgedOffset(it, 12)) }
  }

  @Test fun queryIsEmptyPutAndOnlyAcceptsVerifiedIncompleteOrCompleteResponses() = runBlocking {
    for ((code, ranges, expected) in listOf(Triple(308, emptyArray<String>(), 0L),
      Triple(308, arrayOf("bytes=0-2"), 3L), Triple(200, emptyArray(), 12L), Triple(201, emptyArray(), 12L),
      Triple(204, emptyArray(), null), Triple(202, emptyArray(), null), Triple(404, emptyArray(), null),
      Triple(200, arrayOf("bytes=0-2"), null), Triple(308, arrayOf("bytes=3-4"), null))) {
      var requests = 0
      MuxUploadSdk.useOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
        requests++
        val request = chain.request()
        assertEquals("PUT", request.method)
        assertEquals("bytes */12", request.header("Content-Range"))
        assertEquals(0L, request.body!!.contentLength())
        val body = Buffer(); request.body!!.writeTo(body); assertEquals(0L, body.size)
        response(code, *ranges).newBuilder().request(request).build()
      }.build())
      if (expected == null) {
        try { GeneratedUploadProtocol.query(info(), 12); fail("Unsupported query $code") }
        catch (_: GeneratedResumeBlockedException) {}
      } else assertEquals(expected, GeneratedUploadProtocol.query(info(), 12))
      assertEquals(1, requests)
    }
  }

  @Test fun generatedChunkProgressUsesPartialRangeAndAmbiguityIsNeverRetried() = runBlocking {
    val owned = SdrGeneratedFile.allocate(RuntimeEnvironment.getApplication().cacheDir, 1)!!
    owned.file.writeText("generated payload"); assertTrue(owned.recordValidation())
    val state = UploadPreparationState().apply { verified = owned }
    val upload = info().update(attempt = UploadAttempt(state, MuxUpload.Progress()))
    val chunk = ChunkWorker.Chunk(4, 7, 12, 4, byteArrayOf(4,5,6,7))
    try {
      for ((code, ranges, expected) in listOf(Triple(308, arrayOf("bytes=0-5"), 2L),
        Triple(308, emptyArray<String>(), null), Triple(308, arrayOf("bytes=0-3"), null),
        Triple(308, arrayOf("bytes=0-8"), null), Triple(200, emptyArray(), null),
        Triple(204, emptyArray(), null), Triple(503, emptyArray(), null))) {
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
          try { worker.upload(); fail("Ambiguous chunk $code") } catch (_: GeneratedResumeBlockedException) {}
          assertTrue(progress.replayCache.isEmpty())
        } else {
          assertEquals(expected, worker.upload().bytesUploaded)
          assertEquals(expected, progress.replayCache.single().bytesUploaded)
        }
        assertEquals(1, requests)
      }
      response(200).use { assertEquals(4L, GeneratedUploadProtocol.acknowledge(it, chunk.copy(startByte = 8, endByte = 11))) }
    } finally { owned.delete() }
  }
}
