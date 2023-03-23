package com.mux.video.upload.internal

import android.net.Uri
import android.os.SystemClock
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.internal.network.asCountingRequestBody
import com.mux.video.upload.internal.network.sliceOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.util.*
import java.util.concurrent.atomic.AtomicReference

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

    // TODO: This should be done from a factory method, taking a stream and a slice range
    val uploadJob = outerScope.async {
      try {
        val startTime = Date().time
        var bytesSent: Long = 0
        do {
          // The last chunk will almost definitely be smaller than a whole chunk
          val bytesLeft = fileSize - bytesSent
          val chunkSize = if (uploadInfo.chunkSize > bytesLeft) {
            bytesLeft.toInt()
          } else {
            uploadInfo.chunkSize
          }

          val chunk = ChunkWorker.Chunk(
            contentLength = chunkSize,
            startByte = bytesSent,
            endByte = bytesSent + chunkSize,
            totalFileSize = fileSize,
            sliceStream = fileStream.sliceOf(chunkSize)
          )
          val chunkResult = createWorkerForSlice(
            chunk = chunk,
            uploadInfo = uploadInfo,
            progressChannel = Channel {} // TODO: Receive this
          ).doUpload()

          bytesSent += chunkResult.bytesUploaded
          // TODO: Really we should listen to the chunk's progress and delegate to progressChannel
          val intermediateProgress = MuxUpload.State(
            bytesUploaded = bytesSent,
            totalBytes = fileSize,
            updatedTime = chunkResult.updatedTime,
            startTime = startTime
          )
          progressChannel.send(intermediateProgress)
        } while (bytesSent < fileSize)

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
        errorChannel.send(e)
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
    videoMimeType = uploadInfo.videoMimeType,
    remoteUri = uploadInfo.remoteUri,
    progressChannel = progressChannel,
  )

  private fun <T> callbackChannel() =
    Channel<T>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) { }

  /**
   * Uploads one single file, reporting progress as it goes, and returning the final state when the
   * upload completes. The worker is only responsible for doing the upload and accurately reporting
   * state/errors. Owning objects handle errors, delegate
   */
  internal class ChunkWorker(
    val chunk: Chunk,
    val videoMimeType: String,
    val remoteUri: Uri,
    val progressChannel: Channel<MuxUpload.State>,
  ) {
    companion object {
      // Progress updates are only sent once in this time frame. The latest event is always sent
      const val EVENT_DEBOUNCE_DELAY_MS: Long = 100
    }

    private val logger get() = MuxUploadSdk.logger
    private val updateCallersJob: AtomicReference<Job> = AtomicReference(null)

    @Throws
    suspend fun doUpload(): MuxUpload.State {
      val startTime = SystemClock.elapsedRealtime()
      return supervisorScope {
        val stream = chunk.sliceStream
        val chunkSize = chunk.endByte - chunk.startByte
        val httpClient = MuxUploadSdk.httpClient()
        val putBody =
          stream.asCountingRequestBody(videoMimeType.toMediaTypeOrNull(), chunkSize) { bytes ->
            val elapsedRealtime = SystemClock.elapsedRealtime() // Do this before switching threads
            // We update in a job with a delay() to debounce these events, which come very quickly
            val start = updateCallersJob.compareAndSet(
              null, newUpdateCallersJob(
                uploadedBytes = bytes,
                totalBytes = chunkSize.toLong(),
                startTime = startTime,
                endTime = elapsedRealtime,
                coroutineScope = this
              )
            )
            if (start) {
              updateCallersJob.get()?.start()
            }
          } // countingFileBody callback

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
        val httpResponse = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
        logger.v("MuxUpload", "Uploaded $httpResponse")
        val finalState =
          MuxUpload.State(
            chunkSize.toLong(),
            chunkSize.toLong(),
            startTime,
            SystemClock.elapsedRealtime()
          )

        // Cancel progress updates and make sure no one is stuck listening for more
        updateCallersJob.get()?.cancel()
        progressChannel.send(finalState)
        progressChannel.close()
        finalState
      } // supervisorScope
    } // suspend fun doUpload

    private fun newUpdateCallersJob(
      uploadedBytes: Long,
      totalBytes: Long,
      startTime: Long,
      endTime: Long,
      coroutineScope: CoroutineScope
    ): Job {
      return coroutineScope.launch(Dispatchers.Default, CoroutineStart.LAZY) {
        // Debounce the progress updates, as the file can be read quite quickly
        delay(EVENT_DEBOUNCE_DELAY_MS)
        val state = MuxUpload.State(uploadedBytes, totalBytes, startTime, endTime)
        progressChannel.trySend(state)
        updateCallersJob.set(null)
      }
    }

    internal data class Chunk(
      val startByte: Long,
      val endByte: Long,
      val totalFileSize: Long,
      val contentLength: Int,
      val sliceStream: InputStream
    )
  } // class Worker
}
