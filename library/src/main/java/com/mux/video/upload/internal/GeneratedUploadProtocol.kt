package com.mux.video.upload.internal

import com.mux.video.upload.MuxUploadSdk
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okio.BufferedSink
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Verified on small Mux test Direct Uploads; never follows an ambiguous response. */
internal object GeneratedUploadProtocol {
  private var originalClient: OkHttpClient? = null
  private var transportClient: OkHttpClient? = null

  @Synchronized internal fun httpClient(): OkHttpClient {
    val client = MuxUploadSdk.httpClient()
    if (originalClient !== client) {
      transportClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
      originalClient = client
    }
    return checkNotNull(transportClient)
  }

  suspend fun query(upload: UploadInfo, total: Long): Long {
    require(total > 0)
    val body = object : RequestBody() {
      override fun contentType(): MediaType? = null
      override fun contentLength() = 0L
      override fun writeTo(sink: BufferedSink) {}
      override fun isOneShot() = true
    }
    val request = Request.Builder().url(upload.remoteUri.toString())
      .put(body).header("Content-Range", "bytes */$total").build()
    return executeUploadRequest(request, httpClient()).use { response ->
      when (response.code) {
        308 -> acknowledgedOffset(response, total)
        200, 201 -> if (response.headers.values("Range").isEmpty()) total else blocked()
        else -> failedResponse(response.code)
      }
    }
  }

  fun acknowledge(response: Response, chunk: ChunkWorker.Chunk): Long {
    val next = when (response.code) {
      308 -> acknowledgedOffset(response, chunk.totalFileSize)
      200, 201 -> if (chunk.endByte + 1 == chunk.totalFileSize &&
        response.headers.values("Range").isEmpty()) chunk.totalFileSize else blocked()
      else -> failedResponse(response.code)
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

  private fun failedResponse(code: Int): Nothing {
    if (code == 408 || code == 429 || code in 500..599)
      throw IOException("Generated upload request failed: $code")
    blocked()
  }

  private fun blocked(): Nothing = throw GeneratedResumeBlockedException()
}

internal suspend fun executeUploadRequest(request: Request, client: OkHttpClient): Response = suspendCancellableCoroutine { continuation ->
  val call = client.newCall(request)
  continuation.invokeOnCancellation { call.cancel() }
  call.enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
    override fun onResponse(call: Call, response: Response) {
      continuation.resume(response) { _, value, _ -> value.close() }
    }
  })
}
