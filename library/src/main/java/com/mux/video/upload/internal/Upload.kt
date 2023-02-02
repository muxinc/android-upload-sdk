package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.MuxVodUploadSdk
import com.mux.video.upload.api.MuxVodUpload
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
  val lastKnownState: MuxVodUpload.State?,
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
      Channel<MuxVodUpload.State>(capacity = Channel.UNLIMITED) { undelivered(it) }
    val progressChannel =
      Channel<MuxVodUpload.State>(capacity = Channel.UNLIMITED) { undelivered(it) }
    val errorChannel = Channel<Exception>(capacity = Channel.UNLIMITED) { undelivered(it) }
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

  private fun <T> undelivered(t: T) {
    MuxVodUploadSdk.logger.w(msg = "Undelivered state msg $t")
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
        // TODO: Prepare upload (Maybe by launch()ing coroutines on main thread to update the mgr etc)
        val httpResponse = withContext(Dispatchers.IO) {
          val httpClient = MuxVodUploadSdk.httpClient()
          // TODO: Http
          "dummy object"
        } // withContext(Dispatchers.IO)

        val finalState = MuxVodUpload.State(0, 0)
        finalState
      } // supervisorScope
    }
  }
}

/**
 * Return a new [UploadInfo] with the given data overwritten. Any argument not provided will be
 * copied from the original object.
 */
@JvmSynthetic
internal fun UploadInfo.update(
  remoteUri: Uri = this.remoteUri,
  file: File = this.file,
  lastKnownState: MuxVodUpload.State? = this.lastKnownState,
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
  lastKnownState,
  chunkSize,
  retriesPerChunk,
  retryBaseTimeMs,
  uploadJob,
  successChannel,
  progressChannel,
  errorChannel
)
