package com.mux.video.upload.internal

import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.internal.network.asCountingRequestBody
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response

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
    const val EVENT_DEBOUNCE_DELAY_MS: Long = 200

    @JvmSynthetic
    internal fun create(
      chunk: Chunk,
      uploadInfo: UploadInfo,
      videoMimeType: String,
      progressFlow: MutableSharedFlow<MuxUpload.Progress>,
    ): ChunkWorker = ChunkWorker(chunk, uploadInfo, videoMimeType, progressFlow)
  }

  private var mostRecentUploadBytes = 0L
  private var updateCallersJob: Job? = null

  @Throws
  suspend fun upload(): MuxUpload.Progress {
    currentCoroutineContext().ensureActive()
    val (finalState, response) = doUpload()
    return response.use {
      val acknowledged = finalState.copy(bytesUploaded = UploadProtocol.acknowledge(it, chunk))
      progressFlow.emit(acknowledged)
      acknowledged
    }
  }

  @Throws
  private suspend fun doUpload(): Pair<MuxUpload.Progress, Response> {
    val startTime = System.currentTimeMillis()

    return supervisorScope {
      val stream = chunk.sliceData
      val chunkSize = chunk.endByte - chunk.startByte + 1
      val httpClient = UploadProtocol.httpClient()

      val putBody =
        stream.asCountingRequestBody(videoMimeType.toMediaTypeOrNull(), chunkSize, oneShot = true) { bytes ->
          synchronized(this@ChunkWorker) {
            mostRecentUploadBytes = bytes
            if (updateCallersJob == null) updateCallersJob = launch {
              delay(EVENT_DEBOUNCE_DELAY_MS)
              val value = synchronized(this@ChunkWorker) {
                updateCallersJob = null
                mostRecentUploadBytes
              }
              progressFlow.emit(MuxUpload.Progress(bytesUploaded = value, totalBytes = chunkSize,
                startTime = startTime, updatedTime = System.currentTimeMillis()))
            }
          }
        }

      val request = Request.Builder()
        .url(uploadInfo.remoteUri.toString())
        .put(putBody)
        .header("Content-Type", videoMimeType)
        .header(
          "Content-Range",
          "bytes ${chunk.startByte}-${chunk.endByte}/${chunk.totalFileSize}"
        )
        .build()

      val httpResponse = try { executeUploadRequest(request, httpClient) }
        finally { updateCallersJob?.cancel() }
      try {
        val finalState = MuxUpload.Progress(
          bytesUploaded = chunkSize,
          totalBytes = chunkSize,
          startTime = startTime,
          updatedTime = System.currentTimeMillis()
        )
        Pair(finalState, httpResponse)
      } catch (e: Exception) {
        httpResponse.close()
        throw e
      }
    } // supervisorScope
  } // suspend fun doUpload

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
