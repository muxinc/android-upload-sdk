package com.mux.video.upload.internal

import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.internal.network.asCountingRequestBody
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import kotlin.coroutines.resumeWithException
import okhttp3.Response
import java.io.IOException

/**
 * Uploads one single chunk, reporting progress as it goes, and returning the final state when the
 * upload completes. The worker is only responsible for doing the upload and accurately reporting
 * state/errors. Owning objects handle errors, delegate
 */
internal class ChunkWorker private constructor(
  private val chunk: Chunk,
  private val uploadInfo: UploadInfo,
  private val videoMimeType: String,
  private val progressFlow: MutableSharedFlow<MuxUpload.Progress>,
) {
  companion object {
    // Progress updates are only sent once in this time frame. The latest event is always sent
    const val EVENT_DEBOUNCE_DELAY_MS: Long = 200
    val ACCEPTABLE_STATUS_CODES = listOf(200, 201, 202, 204, 308)
    val RETRYABLE_STATUS_CODES = listOf(408, 502, 503, 504)

    @JvmSynthetic
    internal fun create(
      chunk: Chunk,
      uploadInfo: UploadInfo,
      videoMimeType: String,
      progressFlow: MutableSharedFlow<MuxUpload.Progress>,
    ): ChunkWorker = ChunkWorker(chunk, uploadInfo, videoMimeType, progressFlow)
  }

  private val logger get() = MuxUploadSdk.logger

  private var mostRecentUploadState: RecentState? = null
  private var updateCallersJob: Job? = null

  @Throws
  suspend fun upload(): MuxUpload.Progress {
    val generated = uploadInfo.attempt?.preparation?.verified != null
    // An uncertain generated response is reconciled by the next attempt's status query.
    val moreRetries = { triesSoFar: Int -> !generated && triesSoFar < uploadInfo.retriesPerChunk }
    suspend fun tryUpload(triesSoFar: Int): Result<MuxUpload.Progress> {
      try {
        currentCoroutineContext().ensureActive()
        val (finalState, httpResponse) = doUpload()
        httpResponse.use {
          if (generated) {
            val acknowledged = finalState.copy(bytesUploaded = GeneratedUploadProtocol.acknowledge(it, chunk))
            progressFlow.emit(acknowledged)
            return Result.success(acknowledged)
          }
          if (ACCEPTABLE_STATUS_CODES.contains(it.code)) return Result.success(finalState)
          val failure = IOException("Upload request failed: ${it.code}/${it.message}")
          if (it.code !in RETRYABLE_STATUS_CODES || !moreRetries(triesSoFar)) return Result.failure(failure)
        }
        return tryUpload(triesSoFar + 1)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return if (moreRetries(triesSoFar)) {
          // Still have more retries so try again
          tryUpload(triesSoFar + 1)
        } else {
          Result.failure(e)
        }
      }
    }

    currentCoroutineContext().ensureActive()
    return tryUpload(0).getOrThrow()
  }

  @Throws
  private suspend fun doUpload(): Pair<MuxUpload.Progress, Response> {
    val startTime = System.currentTimeMillis()

    return supervisorScope {
      val stream = chunk.sliceData
      val chunkSize = chunk.endByte - chunk.startByte + 1
      val httpClient = MuxUploadSdk.httpClient().let { client ->
        if (uploadInfo.attempt?.preparation?.verified != null)
          client.newBuilder().followRedirects(false).followSslRedirects(false).build() else client
      }

      val putBody =
        stream.asCountingRequestBody(videoMimeType.toMediaTypeOrNull(), chunkSize) { bytes ->
          if (uploadInfo.attempt?.preparation?.verified != null) return@asCountingRequestBody
          val elapsedRealtime = System.currentTimeMillis()
          // This process happens really fast, so we debounce the callbacks using a coroutine.
          // If there's no job to update callers, create one. That job delays for a set duration
          // then sends a message out on the progress flow with the most-recent known progress
          synchronized(this) { // Synchronize checking/creating update jobs
            mostRecentUploadState = RecentState(elapsedRealtime, bytes)
            if (updateCallersJob == null) {
              updateCallersJob = async {
                // Update callers at most once every EVENT_DEBOUNCE_DELAY
                delay(EVENT_DEBOUNCE_DELAY_MS)
                val currentState = MuxUpload.Progress(
                  bytesUploaded = mostRecentUploadState?.uploadBytes ?: 0,
                  totalBytes = chunkSize,
                  startTime = startTime,
                  updatedTime = elapsedRealtime,
                )
                progressFlow.emit(currentState)
                // synchronize again since we're on a different worker inside async { }
                synchronized(this) { updateCallersJob = null }
              } // ..async { ...
            } // if (updateCallersJob == null)
          } // synchronized(this)
        } // stream.asCountingRequestBody

      val request = Request.Builder()
        .url(uploadInfo.remoteUri.toString())
        .put(putBody)
        .header("Content-Type", videoMimeType)
        .header(
          "Content-Range",
          "bytes ${chunk.startByte}-${chunk.endByte}/${chunk.totalFileSize}"
        )
        .build()

      if (uploadInfo.attempt?.preparation?.verified == null) logger.v("MuxUpload", "Uploading with request $request")
      val call = httpClient.newCall(request)
      val httpResponse = suspendCancellableCoroutine<Response> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
          override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
          override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
          }
        })
      }
      try {
        if (uploadInfo.attempt?.preparation?.verified == null) logger.v("MuxUpload", "Chunk Response: $httpResponse")
        val finalState = MuxUpload.Progress(
          bytesUploaded = chunkSize,
          totalBytes = chunkSize,
          startTime = startTime,
          updatedTime = System.currentTimeMillis()
        )
        // Cancel progress updates and make sure no one is stuck listening for more
        updateCallersJob?.cancel()
        if (uploadInfo.attempt?.preparation?.verified == null) progressFlow.emit(finalState)
        Pair(finalState, httpResponse)
        } catch (e: Exception) {
        httpResponse.close()
        throw e
      }
    } // supervisorScope
  } // suspend fun doUpload

  private data class RecentState(val updatedTime: Long, val uploadBytes: Long)

  internal data class Chunk(
    val startByte: Long,
    val endByte: Long,
    val totalFileSize: Long,
    val contentLength: Int,
    val sliceData: ByteArray,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Chunk

      if (startByte != other.startByte) return false
      if (endByte != other.endByte) return false
      if (totalFileSize != other.totalFileSize) return false
      if (contentLength != other.contentLength) return false
      if (!sliceData.contentEquals(other.sliceData)) return false

      return true
    }

    override fun hashCode(): Int {
      var result = startByte.hashCode()
      result = 31 * result + endByte.hashCode()
      result = 31 * result + totalFileSize.hashCode()
      result = 31 * result + contentLength
      result = 31 * result + sliceData.contentHashCode()
      return result
    }
  }
} // class Worker
