package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.MuxVodUploadSdk
import com.mux.video.upload.api.MuxVodUpload
import com.mux.video.upload.network.asCountingFileBody
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.Request
import java.io.File

/**
 * This object is the SDK's internal representation of an upload that is in-progress. The public
 * object is [MuxVodUpload], which is backed by an instance of this object.
 *
 * This object is immutable. To create an updated version use [update]. The Upload Manager can
 * update the internal state of its jobs based on the content of this object
 */
internal data class UploadInfo(
  val remoteUri: Uri,
  val file: File,
  val videoMimeType: String,
  val chunkSize: Long,
  val retriesPerChunk: Int,
  val retryBaseTimeMs: Long,
  @JvmSynthetic internal val uploadJob: Deferred<Result<MuxVodUpload.State>>?,
  @JvmSynthetic internal val successChannel: Channel<MuxVodUpload.State>?,
  @JvmSynthetic internal val progressChannel: Channel<MuxVodUpload.State>?,
  @JvmSynthetic internal val errorChannel: Channel<Exception>?,
)

/**
 * Creates a new Upload Job for this
 */
@JvmSynthetic
internal fun createUploadJob(upload: UploadInfo): UploadInfo {
  return UploadJobFactory.createUploadJob(upload, CoroutineScope(Dispatchers.Default))
}

/**
 * Creates upload coroutine jobs, which handle uploading a single file and reporting/delegating
 * the state of the upload. To cancel, just call [Deferred.cancel]
 */
private object UploadJobFactory {
  fun createUploadJob(uploadInfo: UploadInfo, outerScope: CoroutineScope): UploadInfo {
    val successChannel =
      Channel<MuxVodUpload.State>(capacity = Channel.UNLIMITED) { logOrphanChannelMsg(it) }
    val progressChannel =
      Channel<MuxVodUpload.State>(capacity = Channel.UNLIMITED) { logOrphanChannelMsg(it) }
    val errorChannel = Channel<Exception>(capacity = Channel.UNLIMITED) { logOrphanChannelMsg(it) }
    val worker = Worker(uploadInfo)

    // Put them all together
    val uploadJob = outerScope.async {
      try {
        val finalState = worker.doUpload()
        Result.success(finalState)
      } catch (e: Exception) {
        MuxVodUploadSdk.logger.e(msg = "Upload of ${uploadInfo.file} failed");
        Result.failure(e)
      }
    }

    return uploadInfo.update(
      successChannel = successChannel,
      progressChannel = progressChannel,
      errorChannel = errorChannel,
      uploadJob = uploadJob
    )
  }

  private fun <T> logOrphanChannelMsg(t: T) {
    MuxVodUploadSdk.logger.v(msg = "Undelivered state msg $t")
  }
}

/**
 * Uploads one single file, reporting progress as it goes, and returning the final state when the
 * upload completes. The worker is only responsible for doing the upload and accurately reporting
 * state/errors. Owning objects handle errors, delegate
 */
private class Worker(val uploadInfo: UploadInfo) {

  @Throws
  suspend fun doUpload(): MuxVodUpload.State {
    return supervisorScope {
      val fileSize = uploadInfo.file.length()
      val httpClient = MuxVodUploadSdk.httpClient()
      val fileBody = uploadInfo.file.asCountingFileBody(uploadInfo.videoMimeType) { bytes ->
        val state = MuxVodUpload.State(bytes, fileSize)
        launch(Dispatchers.Main) { uploadInfo.progressChannel?.send(state) }
      }
      val request = Request.Builder()
        .url(uploadInfo.remoteUri.toString())
        .put(fileBody)
        .build()

      val httpResponse = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
      MuxVodUploadSdk.logger.v("UploadWorker", "With Response: $httpResponse")

      MuxVodUpload.State(fileSize, fileSize)
    } // supervisorScope
  } // suspend fun doUpload
} // class Worker

/**
 * Return a new [UploadInfo] with the given data overwritten. Any argument not provided will be
 * copied from the original object.
 */
@JvmSynthetic
internal fun UploadInfo.update(
  remoteUri: Uri = this.remoteUri,
  file: File = this.file,
  videoMimeType: String = this.videoMimeType,
  chunkSize: Long = this.chunkSize,
  retriesPerChunk: Int = this.retriesPerChunk,
  retryBaseTimeMs: Long = this.retryBaseTimeMs,
  uploadJob: Deferred<Result<MuxVodUpload.State>>? = this.uploadJob,
  successChannel: Channel<MuxVodUpload.State>? = this.successChannel,
  progressChannel: Channel<MuxVodUpload.State>? = this.progressChannel,
  errorChannel: Channel<Exception>? = this.errorChannel,
) = UploadInfo(
  remoteUri,
  file,
  videoMimeType,
  chunkSize,
  retriesPerChunk,
  retryBaseTimeMs,
  uploadJob,
  successChannel,
  progressChannel,
  errorChannel
)
