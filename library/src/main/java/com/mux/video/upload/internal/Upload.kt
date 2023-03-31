package com.mux.video.upload.internal

import android.util.Log
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.util.*

/**
 * Creates a new Upload Job for this
 */
@JvmSynthetic
internal fun createUploadJob(upload: UploadInfo): UploadInfo {
  MuxUploadSdk.logger.d("MuxUpload", "Creating job for: $upload")
  return MuxUploadSdk.uploadJobFactory()
    .createUploadJob(upload, CoroutineScope(Dispatchers.Default))
}

/**
 * Creates upload coroutine jobs, which handle uploading a single file and reporting/delegating
 * the state of the upload. To cancel, just call [Deferred.cancel]
 *
 * Create instances of this class via [createUploadJob]
 */
internal class UploadJobFactory private constructor() {
  private val logger get() = MuxUploadSdk.logger

  companion object {
    @JvmSynthetic
    internal fun create() = UploadJobFactory()
  }

  fun createUploadJob(uploadInfo: UploadInfo, outerScope: CoroutineScope): UploadInfo {
    logger
    val successChannel = callbackChannel<MuxUpload.State>()
    val progressChannel = callbackChannel<MuxUpload.State>()
    val errorChannel = callbackChannel<Exception>()
    val fileStream = BufferedInputStream(FileInputStream(uploadInfo.file))
    val fileSize = uploadInfo.file.length()

    val uploadJob = outerScope.async {
      try {
        var chunkNr = 0
        val startTime = Date().time
        var totalBytesSent: Long = 0
        val chunkBuffer = ByteArray(uploadInfo.chunkSize)
        do {
          // The last chunk will almost definitely be smaller than a whole chunk
          val bytesLeft = fileSize - totalBytesSent + 1
          val chunkSize = if (uploadInfo.chunkSize > bytesLeft) {
            bytesLeft.toInt()
          } else {
            uploadInfo.chunkSize
          }
          val chunk = ChunkWorker.Chunk(
            contentLength = chunkSize,
            startByte = totalBytesSent,
            endByte = totalBytesSent + chunkSize - 1,
            totalFileSize = fileSize,
            sliceData = chunkBuffer,
          )
          val chunkResult = createWorkerForSlice(chunk, uploadInfo, callbackChannel()).doUpload()
          Log.d("fuck", "Returned chunk result $chunkResult")
          Log.d("UploadJobFactory", "Chunk number ${chunkNr++}")

          totalBytesSent += chunkResult.bytesUploaded
          val intermediateProgress = MuxUpload.State(
            bytesUploaded = totalBytesSent,
            totalBytes = fileSize,
            updatedTime = chunkResult.updatedTime,
            startTime = startTime
          )
          progressChannel.send(intermediateProgress)
          Log.d("fuck", "Looped once in the chunk loop")
        } while (totalBytesSent < fileSize)
        val finalState = MuxUpload.State(
          bytesUploaded = fileSize,
          totalBytes = fileSize,
          startTime = startTime,
          updatedTime = Date().time
        )
        successChannel.send(finalState)
        Result.success(finalState)
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("MuxUpload", "Upload of ${uploadInfo.file} failed", e)
        errorChannel.trySend(e)
        @Suppress("BlockingMethodInNonBlockingContext") // the streams we use don't block on close
        fileStream.close()
        Result.failure(e)
      } finally {
        MainScope().launch { MuxUploadManager.jobFinished(uploadInfo) }
      }
    }

    return uploadInfo.update(
      successChannel = successChannel,
      progressChannel = progressChannel,
      errorChannel = errorChannel,
      uploadJob = uploadJob
    )
  }

  private fun createWorkerForSlice(
    chunk: ChunkWorker.Chunk,
    uploadInfo: UploadInfo,
    progressChannel: Channel<MuxUpload.State>
  ): ChunkWorker = ChunkWorker(
    chunk = chunk,
    maxRetries = uploadInfo.retriesPerChunk,
    videoMimeType = uploadInfo.videoMimeType,
    remoteUri = uploadInfo.remoteUri,
    progressChannel = progressChannel,
  )

  private fun <T> callbackChannel() =
    Channel<T>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) { }
}
