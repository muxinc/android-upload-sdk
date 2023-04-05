package com.mux.video.upload.internal

import android.os.SystemClock
import android.util.Log
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.util.*

/**
 * Creates a new Upload Job for the given [UploadInfo]. The job is started as soon as it is created.
 * To pause a job, save its last-known state in UploadPersistence and cancel the [Job] in the
 * [UploadInfo] returned by this function
 */
@JvmSynthetic
internal fun startUploadJob(upload: UploadInfo): UploadInfo {
  MuxUploadSdk.logger.d("MuxUpload", "Creating job for: $upload")
  return MuxUploadSdk.uploadJobFactory()
    .createUploadJob(upload, CoroutineScope(Dispatchers.Default))
}

/**
 * Creates upload coroutine jobs, which handle uploading a single file and reporting/delegating
 * the state of the upload. To cancel, just call [Deferred.cancel]
 * This class is not intended to be used from outside the SDK
 */
internal class UploadJobFactory private constructor(
  val createWorker: (ChunkWorker.Chunk, UploadInfo, MutableSharedFlow<MuxUpload.State>) -> ChunkWorker =
    this::createWorkerForSlice
) {
  private val logger get() = MuxUploadSdk.logger

  companion object {
    @JvmSynthetic
    internal fun create() = UploadJobFactory()

    @Suppress("unused") // It's used by method-reference, which the linter doesn't see
    @JvmSynthetic
    private fun createWorkerForSlice(
      chunk: ChunkWorker.Chunk,
      uploadInfo: UploadInfo,
      progressChannel: MutableSharedFlow<MuxUpload.State>
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
    val successChannel = callbackFlow<MuxUpload.State>()
    val overallProgressChannel = callbackFlow<MuxUpload.State>()
    val errorChannel = callbackFlow<Exception>()
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

          val chunkProgressChannel = callbackFlow<MuxUpload.State>()
          var updateProgressJob: Job? = null
          try {
            // Bounce progress updates to callers
            updateProgressJob = launch {
              chunkProgressChannel.collect { chunkProgress ->
                overallProgressChannel.emit(
                  MuxUpload.State(
                    bytesUploaded = chunkProgress.bytesUploaded + totalBytesSent,
                    totalBytes = fileSize,
                    startTime = startTime,
                    updatedTime = chunkProgress.updatedTime
                  )
                ) // overallProgressChannel.emit(
              } // chunkProgressChannel.collect {
            }

            val chunkResult = createWorker(chunk, uploadInfo, chunkProgressChannel).upload()
            Log.d("UploadJobFactory", "Chunk number ${chunkNr++}")

            totalBytesSent += chunkResult.bytesUploaded
            val intermediateProgress = MuxUpload.State(
              bytesUploaded = totalBytesSent,
              totalBytes = fileSize,
              updatedTime = chunkResult.updatedTime,
              startTime = startTime
            )
            overallProgressChannel.emit(intermediateProgress)
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
        successChannel.emit(finalState)
        Result.success(finalState)
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("MuxUpload", "Upload of ${uploadInfo.file} failed", e)
        errorChannel.emit(e)
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

  private fun <T> callbackFlow() = MutableSharedFlow<T>()
    //Channel<T>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) { }
}
