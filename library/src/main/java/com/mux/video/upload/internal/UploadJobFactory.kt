package com.mux.video.upload.internal

import android.os.SystemClock
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
internal class UploadJobFactory private constructor(
  val createWorker: (ChunkWorker.Chunk, UploadInfo, Channel<MuxUpload.State>) -> ChunkWorker =
    this::createWorkerForSlice
) {
  private val logger get() = MuxUploadSdk.logger

  companion object {
    @JvmSynthetic
    internal fun create() = UploadJobFactory()

    @JvmSynthetic
    internal fun create(
      workerFactory: (ChunkWorker.Chunk, UploadInfo, Channel<MuxUpload.State>) -> ChunkWorker
    ) = UploadJobFactory(workerFactory)

    @Suppress("unused") // It's used by method-reference, which the linter doesn't see
    @JvmSynthetic
    private fun createWorkerForSlice(
      chunk: ChunkWorker.Chunk,
      uploadInfo: UploadInfo,
      progressChannel: Channel<MuxUpload.State>
    ): ChunkWorker =
      ChunkWorker(
        chunk = chunk,
        maxRetries = uploadInfo.retriesPerChunk,
        videoMimeType = uploadInfo.videoMimeType,
        remoteUri = uploadInfo.remoteUri,
        progressChannel = progressChannel,
      )
  }

  fun createUploadJob(uploadInfo: UploadInfo, outerScope: CoroutineScope): UploadInfo {
    logger
    val successChannel = callbackChannel<MuxUpload.State>()
    val overallProgressChannel = callbackChannel<MuxUpload.State>()
    val errorChannel = callbackChannel<Exception>()
    val fileStream = BufferedInputStream(FileInputStream(uploadInfo.file))
    val fileSize = uploadInfo.file.length()

    val uploadJob = outerScope.async {
      try {
        var chunkNr = 0
        val startTime = SystemClock.elapsedRealtime()
        var totalBytesSent: Long = 0
        val chunkBuffer = ByteArray(uploadInfo.chunkSize)

        do {
          // The last chunk will almost definitely be smaller than a whole chunk
          val bytesLeft = fileSize - totalBytesSent
          val chunkSize = if (uploadInfo.chunkSize > bytesLeft) {
            bytesLeft.toInt()
          } else {
            uploadInfo.chunkSize
          }
          //read-in a chunk
          val fileReadSize = withContext(Dispatchers.IO) {
            fileStream.read(chunkBuffer, 0, chunkSize)
          }
          if (fileReadSize != chunkSize) { // Guaranteed unless the file was changed under us or sth
            throw IllegalStateException("expected to read $chunkSize bytes, but read $fileReadSize")
          }

          val chunk = ChunkWorker.Chunk(
            contentLength = chunkSize,
            startByte = totalBytesSent,
            endByte = totalBytesSent + chunkSize - 1,
            totalFileSize = fileSize,
            sliceData = chunkBuffer,
          )

          val chunkProgressChannel = callbackChannel<MuxUpload.State>()
          var updateProgressJob: Job? = null
          try {
            // Bounce progress updates to callers
            updateProgressJob = launch {
              for (chunkProgress in chunkProgressChannel) {
                overallProgressChannel.send(
                  MuxUpload.State(
                    bytesUploaded = chunkProgress.bytesUploaded + totalBytesSent,
                    totalBytes = fileSize,
                    startTime = startTime,
                    updatedTime = chunkProgress.updatedTime
                  )
                ) // overallProgress.send(
              } // for (chunkProgress in chunkProgressChannel)
            }

            val chunkResult = createWorker(chunk, uploadInfo, chunkProgressChannel).doUpload()
            Log.d("UploadJobFactory", "Chunk number ${chunkNr++}")

            totalBytesSent += chunkResult.bytesUploaded
            val intermediateProgress = MuxUpload.State(
              bytesUploaded = totalBytesSent,
              totalBytes = fileSize,
              updatedTime = chunkResult.updatedTime,
              startTime = startTime
            )
            overallProgressChannel.send(intermediateProgress)
          } finally {
            updateProgressJob?.cancel()
          }
        } while (totalBytesSent < fileSize)
        val finalState = MuxUpload.State(
          bytesUploaded = fileSize,
          totalBytes = fileSize,
          startTime = startTime,
          updatedTime = SystemClock.elapsedRealtime()
        )
        successChannel.send(finalState)
        Result.success(finalState)
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("MuxUpload", "Upload of ${uploadInfo.file} failed", e)
        errorChannel.trySend(e)
        Result.failure(e)
      } finally {
        @Suppress("BlockingMethodInNonBlockingContext") // the streams we use don't block on close
        fileStream.close()
        MainScope().launch { MuxUploadManager.jobFinished(uploadInfo) }
      }
    }

    return uploadInfo.update(
      successChannel = successChannel,
      progressChannel = overallProgressChannel,
      errorChannel = errorChannel,
      uploadJob = uploadJob
    )
  }

  private fun <T> callbackChannel() =
    Channel<T>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) { }
}
