package com.mux.video.upload.internal

import android.os.Build
import android.util.Log
import com.mux.video.upload.BuildConfig
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
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
    private const val MIME_TYPE_GENERIC_VIDEO = "video/*"

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
      uploadInfo = uploadInfo,
      videoMimeType = MIME_TYPE_GENERIC_VIDEO,
      progressFlow = progressFlow,
    )
  }

  fun createUploadJob(uploadInfo: UploadInfo, outerScope: CoroutineScope): UploadInfo {
    logger
    val successFlow = callbackFlow<MuxUpload.Progress>()
    val overallProgressFlow = callbackFlow<MuxUpload.Progress>()
    val errorFlow = callbackFlow<Exception>()
    var fileStream: InputStream = BufferedInputStream(FileInputStream(uploadInfo.inputFile))
    var fileSize = uploadInfo.inputFile.length()
    val metrics = UploadMetrics.create()

    val uploadJob = outerScope.async {
      // This UploadInfo never gets sent outside this coroutine. It contains info related to
      // standardizing the the client doesn't need to know/can't know synchronously
      var internalUploadInfo = uploadInfo

      val startTime = System.currentTimeMillis()
      try {
        // See if the file need to be converted to a standard input.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          val tcx = TranscoderContext.create(internalUploadInfo, MuxUploadManager.appContext!!)
          internalUploadInfo = tcx.process()
          if (tcx.fileTranscoded) {
            fileStream = withContext(Dispatchers.IO) {
              BufferedInputStream(FileInputStream(internalUploadInfo.standardizedFile))
            }
            // This !! is safe by contract: process() will set the standardizedFile
            fileSize = internalUploadInfo.standardizedFile!!.length()
          }
        }

        var totalBytesSent: Long = getAlreadyTransferredBytes(internalUploadInfo)
        Log.d("UploadJobFactory", "totalBytesSent: $totalBytesSent")
        val chunkBuffer = ByteArray(internalUploadInfo.chunkSize)

        // If we're resuming, we must skip to the current file pos
        if (totalBytesSent != 0L) {
          withContext(Dispatchers.IO) { fileStream.skip(totalBytesSent) }
        }

        // Upload each chunk starting from the current head of the stream
        do {
          // The last chunk will almost definitely be smaller than a whole chunk
          val bytesLeft = fileSize - totalBytesSent
          val thisChunkSize = if (internalUploadInfo.chunkSize > bytesLeft) {
            bytesLeft.toInt()
          } else {
            internalUploadInfo.chunkSize
          }
          logger.i("UploadJob", "Trying to read $thisChunkSize bytes")
          //read-in a chunk
          val fileReadSize = withContext(Dispatchers.IO) {
            fileStream.read(chunkBuffer, 0, thisChunkSize)
          }
          if (fileReadSize != thisChunkSize) { // Guaranteed unless the file was changed under us or sth
            throw IllegalStateException("expected to read $thisChunkSize bytes, but read $fileReadSize")
          }

          val chunk = ChunkWorker.Chunk(
            contentLength = thisChunkSize,
            startByte = totalBytesSent,
            endByte = totalBytesSent + thisChunkSize - 1,
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

            val chunkFinalState = createWorker(chunk, internalUploadInfo, chunkProgressFlow).upload()

            totalBytesSent += chunkFinalState.bytesUploaded
            val intermediateProgress = MuxUpload.Progress(
              bytesUploaded = totalBytesSent,
              totalBytes = fileSize,
              updatedTime = chunkFinalState.updatedTime,
              startTime = startTime,
            )
            overallProgressFlow.emit(intermediateProgress)
          } finally {
            updateProgressJob?.cancel()
          }
        } while (totalBytesSent < fileSize)

        // We made it!
        val finalState = createFinalState(fileSize, startTime)
        // report this upload asynchronously (unless a debug build of the SDK)
        @Suppress("KotlinConstantConditions")
        if (BuildConfig.BUILD_TYPE != "debug" && !internalUploadInfo.optOut) {
          launch {
            metrics.reportUpload(
              startTimeMillis = finalState.startTime,
              endTimeMillis = finalState.updatedTime,
              uploadInfo = internalUploadInfo,
            )
          }
        }

        // finish up
        MainScope().launch { MuxUploadManager.jobFinished(internalUploadInfo) }
        successFlow.emit(finalState)
        Result.success(finalState)
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("MuxUpload", "Upload of ${internalUploadInfo.inputFile} failed", e)
        val finalState = createFinalState(fileSize, startTime)
        overallProgressFlow.emit(finalState)
        errorFlow.emit(e)
        MainScope().launch { MuxUploadManager.jobFinished(internalUploadInfo, false) }
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
      updatedTime = System.currentTimeMillis(),
    )
  }

  private fun getAlreadyTransferredBytes(file: UploadInfo): Long = readLastByteForFile(file)

  private fun <T> callbackFlow() =
    MutableSharedFlow<T>(
      replay = 1,
      extraBufferCapacity = 2, // Some slop for UI to miss events
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}
