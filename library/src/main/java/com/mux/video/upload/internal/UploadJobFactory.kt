package com.mux.video.upload.internal

import android.os.SystemClock
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
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
  return MuxUploadSdk.uploadJobFactory()
    .createUploadJob(upload, CoroutineScope(Dispatchers.Default))
}

/**
 * Creates upload coroutine jobs, which handle uploading a single file and reporting/delegating
 * the state of the upload. To cancel, just call [Deferred.cancel]
 * This class is not intended to be used from outside the SDK
 */
internal class UploadJobFactory private constructor(
  val createWorker: (ChunkWorker.Chunk, UploadInfo, MutableSharedFlow<MuxUpload.Progress>) -> ChunkWorker =
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
      progressFlow: MutableSharedFlow<MuxUpload.Progress>
    ): ChunkWorker = ChunkWorker.create(
      chunk = chunk,
      maxRetries = uploadInfo.retriesPerChunk,
      videoMimeType = uploadInfo.videoMimeType,
      remoteUri = uploadInfo.remoteUri,
      progressFlow = progressFlow,
    )
  }

  fun createUploadJob(uploadInfo: UploadInfo, outerScope: CoroutineScope): UploadInfo {
    logger
    val successFlow = callbackFlow<MuxUpload.Progress>()
    val overallProgressFlow = callbackFlow<MuxUpload.Progress>()
    val errorFlow = callbackFlow<Exception>()
    val fileStream = BufferedInputStream(FileInputStream(uploadInfo.file))
    val fileSize = uploadInfo.file.length()

    val uploadJob = outerScope.async {
      val startTime = SystemClock.elapsedRealtime()
      try {
        var totalBytesSent: Long = getAlreadyTransferredBytes(uploadInfo)
        val chunkBuffer = ByteArray(uploadInfo.chunkSize)

        // If we're resuming, we must skip to the current file pos
        if (totalBytesSent != 0L) {
          withContext(Dispatchers.IO) { fileStream.skip(totalBytesSent) }
        }

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

          val chunkProgressFlow = callbackFlow<MuxUpload.Progress>()
          var updateProgressJob: Job? = null
          try {
            // Bounce progress updates to callers
            updateProgressJob = launch {
              chunkProgressFlow.collect { chunkProgress ->
                overallProgressFlow.emit(
                  MuxUpload.Progress(
                    bytesUploaded = chunkProgress.bytesUploaded + totalBytesSent,
                    totalBytes = fileSize,
                    startTime = startTime,
                    updatedTime = chunkProgress.updatedTime,
                  )
                ) // overallProgressChannel.emit(
              } // chunkProgressChannel.collect {
            }

            val chunkResult = createWorker(chunk, uploadInfo, chunkProgressFlow).upload()

            totalBytesSent += chunkResult.bytesUploaded
            val intermediateProgress = MuxUpload.Progress(
              bytesUploaded = totalBytesSent,
              totalBytes = fileSize,
              updatedTime = chunkResult.updatedTime,
              startTime = startTime,
            )
            overallProgressFlow.emit(intermediateProgress)
          } finally {
            updateProgressJob?.cancel()
          }
        } while (totalBytesSent < fileSize)
        val finalState = createFinalState(fileSize, startTime)
        successFlow.emit(finalState)
        MainScope().launch { MuxUploadManager.jobFinished(uploadInfo) }
        Result.success(finalState)
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("MuxUpload", "Upload of ${uploadInfo.file} failed", e)
        val finalState = createFinalState(fileSize, startTime)
        overallProgressFlow.emit(finalState)
        errorFlow.emit(e)
        MainScope().launch { MuxUploadManager.jobFinished(uploadInfo, e !is CancellationException) }
        Result.failure(e)
      } finally {
        @Suppress("BlockingMethodInNonBlockingContext") // the streams we use don't block on close
        fileStream.close()
      }
    }

    return uploadInfo.update(
      successFlow = successFlow,
      progressFlow = overallProgressFlow,
      errorFlow = errorFlow,
      uploadJob = uploadJob,
    )
  }

  private fun createFinalState(fileSize: Long, startTime: Long): MuxUpload.Progress {
    return MuxUpload.Progress(
      bytesUploaded = fileSize,
      totalBytes = fileSize,
      startTime = startTime,
      updatedTime = SystemClock.elapsedRealtime()
    )
  }

  private fun getAlreadyTransferredBytes(file: UploadInfo): Long = readLastByteForFile(file)

  private fun <T> callbackFlow() = MutableSharedFlow<T>()
}
