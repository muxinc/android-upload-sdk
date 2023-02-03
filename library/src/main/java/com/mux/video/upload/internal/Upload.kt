package com.mux.video.upload.internal

import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.network.asCountingFileBody
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import okhttp3.Request

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
    val worker = Worker(uploadInfo)

    val uploadJob = outerScope.async {
      try {
        val finalState = worker.doUpload()
        Result.success(finalState)
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("MuxUpload", "Upload of ${uploadInfo.file} failed", e)
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

  private fun <T> callbackChannel() =
    Channel<T>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) {
      MuxUploadSdk.logger.v(msg = "Undelivered state msg $it")
    }

  /**
   * Uploads one single file, reporting progress as it goes, and returning the final state when the
   * upload completes. The worker is only responsible for doing the upload and accurately reporting
   * state/errors. Owning objects handle errors, delegate
   */
  internal class Worker(val uploadInfo: UploadInfo) {

    private val logger get() = MuxUploadSdk.logger

    @Throws
    suspend fun doUpload(): MuxUpload.State {
      return supervisorScope {
        val fileSize = uploadInfo.file.length()
        val httpClient = MuxUploadSdk.httpClient()
        val fileBody = uploadInfo.file.asCountingFileBody(uploadInfo.videoMimeType) { bytes ->
          val state = MuxUpload.State(bytes, fileSize)
          uploadInfo.progressChannel?.let { launch { it.trySend(state) } }
        }
        val request = Request.Builder()
          .url(uploadInfo.remoteUri.toString())
          .put(fileBody)
          .build()

        logger.v("MuxUpload", "Uploading with request $request")
        val httpResponse = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
        logger.v("MuxUpload", "Uploaded $httpResponse")
        MuxUpload.State(fileSize, fileSize)
      } // supervisorScope
    } // suspend fun doUpload
  } // class Worker
}
