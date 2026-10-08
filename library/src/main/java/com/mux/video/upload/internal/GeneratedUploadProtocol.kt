package com.mux.video.upload.internal

import com.mux.video.upload.MuxUploadSdk
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Verified on small Mux test Direct Uploads; never follows an ambiguous response. */
internal object GeneratedUploadProtocol {
  suspend fun query(upload: UploadInfo, total: Long): Long {
    require(total > 0)
    val request = Request.Builder().url(upload.remoteUri.toString())
      .put(ByteArray(0).toRequestBody()).header("Content-Range", "bytes */$total").build()
    try {
      return execute(request).use { response ->
        when (response.code) {
          308 -> acknowledgedOffset(response, total)
          200, 201 -> if (response.headers.values("Range").isEmpty()) total else blocked()
          else -> blocked()
        }
      }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { blocked() }
  }

  fun acknowledge(response: Response, chunk: ChunkWorker.Chunk): Long {
    val next = when (response.code) {
      308 -> acknowledgedOffset(response, chunk.totalFileSize)
      200, 201 -> if (chunk.endByte + 1 == chunk.totalFileSize &&
        response.headers.values("Range").isEmpty()) chunk.totalFileSize else blocked()
      else -> blocked()
    }
    if (next <= chunk.startByte || next > chunk.endByte + 1) blocked()
    return next - chunk.startByte
  }

  internal fun acknowledgedOffset(response: Response, total: Long): Long {
    val ranges = response.headers.values("Range")
    if (ranges.isEmpty()) return 0
    if (ranges.size != 1) blocked()
    val match = Regex("bytes=0-([0-9]+)").matchEntire(ranges.single()) ?: blocked()
    val end = match.groupValues[1].toLongOrNull() ?: blocked()
    // 308 is incomplete; an endpoint at/past total is contradictory.
    if (end >= total - 1) blocked()
    return end + 1
  }

  private fun blocked(): Nothing = throw GeneratedResumeBlockedException()
}

internal suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
  val call = MuxUploadSdk.httpClient().newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request)
  continuation.invokeOnCancellation { call.cancel() }
  call.enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
    override fun onResponse(call: Call, response: Response) {
      continuation.resume(response) { _, value, _ -> value.close() }
    }
  })
}
