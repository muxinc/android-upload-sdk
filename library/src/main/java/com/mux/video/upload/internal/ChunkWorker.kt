package com.mux.video.upload.internal

import android.net.Uri
import android.os.SystemClock
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.internal.network.asCountingRequestBody
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.HttpRetryException

/**
 * Uploads one single chunk, reporting progress as it goes, and returning the final state when the
 * upload completes. The worker is only responsible for doing the upload and accurately reporting
 * state/errors. Owning objects handle errors, delegate
 */
internal class ChunkWorker(
  val chunk: Chunk,
  val maxRetries: Int,
  val videoMimeType: String,
  val remoteUri: Uri,
  val progressChannel: Channel<MuxUpload.State>,
) {
  companion object {
    // Progress updates are only sent once in this time frame. The latest event is always sent
    const val EVENT_DEBOUNCE_DELAY_MS: Long = 100
    val ACCEPTABLE_STATUS_CODES = listOf(200, 201, 202, 204, 308)
    val RETRYABLE_STATUS_CODES = listOf(408, 502, 503, 504)
  }

  private val logger get() = MuxUploadSdk.logger

  private var mostRecentUploadState: RecentState? = null
  private var updateCallersJob: Job? = null

  @Throws
  suspend fun doUpload(): MuxUpload.State {
    var tries = 0
    do {
      try {
        val (finalState, httpResponse) = uploadInner()
        tries++
        if (ACCEPTABLE_STATUS_CODES.contains(httpResponse.code)) {
          // End Case: Chunk success!
          return finalState
        } else if (RETRYABLE_STATUS_CODES.contains(httpResponse.code)) {
          throw IOException("${httpResponse.code}: ${httpResponse.message}:" +
                  "\n${httpResponse.body?.bytes()?.decodeToString()}")
        }
      } catch (e: Exception) {
        tries++
        if(tries > maxRetries) {
          // End Case: Retries exceeded
          throw e
        }
      }
    } while(true)
  }

  @Throws
  private suspend fun uploadInner(): Pair<MuxUpload.State, Response> {
    val startTime = SystemClock.elapsedRealtime()

    return supervisorScope {
      val stream = chunk.sliceData
      val chunkSize = chunk.endByte - chunk.startByte + 1
      val httpClient = MuxUploadSdk.httpClient()

      val putBody =
        stream.asCountingRequestBody(videoMimeType.toMediaTypeOrNull(), chunkSize) { bytes ->
          val elapsedRealtime = SystemClock.elapsedRealtime() // Do this before switching threads
          // This process happens really fast, so we debounce the callbacks using a coroutine.
          // If there's no job to update callers, create one. That job delays for a set duration
          // then sends a message out on the progress channel with the most-recent known progress
          synchronized(this) { // Synchronize checking/creating update jobs
            mostRecentUploadState = RecentState(elapsedRealtime, bytes)
            if (updateCallersJob == null) {
              updateCallersJob = async {
                // Update callers at most once every EVENT_DEBOUNCE_DELAY
                delay(EVENT_DEBOUNCE_DELAY_MS)
                val currentState = MuxUpload.State(
                  bytesUploaded = mostRecentUploadState?.uploadBytes ?: 0,
                  totalBytes = chunkSize,
                  startTime = startTime,
                  updatedTime = elapsedRealtime,
                )
                progressChannel.send(currentState)
                // synchronize again since we're on a different worker inside async { }
                synchronized(this) { updateCallersJob = null }
              } // ..async { ...
            } // if (updateCallersJob == null)
          } // synchronized(this)
        } // stream.asCountingRequestBody

      val request = Request.Builder()
        .url(remoteUri.toString())
        .put(putBody)
        .header("Content-Type", videoMimeType)
        .header(
          "Content-Range",
          "bytes ${chunk.startByte}-${chunk.endByte}/${chunk.totalFileSize}"
        )
        .build()

      logger.v("MuxUpload", "Uploading with request $request")
      val httpResponse = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute()
      }
      logger.v("MuxUpload", "Chunk Response: $httpResponse")
      val finalState = MuxUpload.State(
        bytesUploaded = chunkSize,
        totalBytes = chunkSize,
        startTime = startTime,
        updatedTime = SystemClock.elapsedRealtime()
      )
      // Cancel progress updates and make sure no one is stuck listening for more
      updateCallersJob?.cancel()
      progressChannel.send(finalState)
      Pair(finalState, httpResponse)
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
