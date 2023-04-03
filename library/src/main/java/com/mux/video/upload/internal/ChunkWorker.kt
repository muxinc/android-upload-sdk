package com.mux.video.upload.internal

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.internal.network.asCountingRequestBody
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Uploads one single file, reporting progress as it goes, and returning the final state when the
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

    val ACCEPTABLE_ERROR_CODES = listOf(200, 201, 202, 204, 308)
    val RETRYABLE_ERROR_CODES = listOf(408, 502, 503, 504)
  }

  private val logger get() = MuxUploadSdk.logger

  // updates from the request body come quickly, we have to debounce the events
  // TODO: This doesn't need to be an AtomicReference (used synchronized instead)
  private var mostRecentUploadState: RecentState? = null
  private var updateCallersJob: Job? = null

  @Throws
  suspend fun doUpload(): MuxUpload.State {
    val updateCallersScope = CoroutineScope(coroutineContext)
    val startTime = SystemClock.elapsedRealtime()

    return supervisorScope {
      val stream = chunk.sliceData
      val chunkSize = chunk.endByte - chunk.startByte + 1
      val httpClient = MuxUploadSdk.httpClient()

      val putBody =
        stream.asCountingRequestBody(videoMimeType.toMediaTypeOrNull(), chunkSize) { bytes ->
          updateCallersScope.launch {
            val elapsedRealtime = SystemClock.elapsedRealtime() // Do this before switching threads
            mostRecentUploadState = RecentState(elapsedRealtime, bytes)

            // This process happens really fast, so we debounce the callbacks using a coroutine.
            // If there's no job to update callers, create one. That job delays for a set duration
            // then sends a message out on the progress channel with the most-recent known progress
            synchronized(this) { // Synchronize checking/creating update jobs
              if (updateCallersJob == null) {
                updateCallersJob = updateCallersScope.async {
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
                } // updateCallersJob = async ()
              } // if (updateCallersJob == null)
            } // synchronized(this)
          }
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
      progressChannel.trySend(finalState)
      finalState
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
