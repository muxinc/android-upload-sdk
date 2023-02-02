package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.api.MuxVodUpload
import kotlinx.coroutines.*
import java.io.File

/**
 * Represents a Mux Video direct upload, which may be in-progress, paused, finished, or not yet
 * started. The state (as reported at the time the object was created) can be read from
 * [lastKnownState], but it may be stale.
 *
 * This object is the SDK's internal representation of an upload that is in-progress. The public
 * object is [MuxVodUpload], which is backed by an instance of this object
 */
internal data class MuxUploadInfo(
  val remoteUri: Uri,
  val file: File,
  val lastKnownState: MuxVodUpload.State?,
  val chunkSize: Long,
  val retriesPerChunk: Int,
  val retryBaseTimeMs: Long,
  @JvmSynthetic internal val uploadJob: Deferred<MuxVodUpload.State>?,
  @JvmSynthetic internal val successCallback: ((MuxVodUpload.State) -> Unit)?,
  @JvmSynthetic internal val progressCallback: ((MuxVodUpload.State) -> Unit)?,
  @JvmSynthetic internal val errorCallback: ((Exception) -> Unit)?,
)

/**
 * Creates a new Upload Job for this
 */
@JvmSynthetic
internal fun createUploadJob(upload: MuxVodUpload): Deferred<MuxVodUpload.State> {
  // TODO: Callback delivers state data
  return UploadJobFactory.createUploadJob(CoroutineScope(Dispatchers.Default))
}

/**
 * Creates upload coroutine jobs, which handle uploading a single file and reporting/delegating
 * the state of the upload. To cancel, just call [Deferred.cancel]
 */
private object UploadJobFactory {
  fun createUploadJob(outerScope: CoroutineScope): Deferred<MuxVodUpload.State> =
    outerScope.async { MuxVodUpload.State(0, 0) }

  /*
   * Fuck my life how do I do this? Ok so we do need to create a MuxVideoUpload for an in-progress
   * upload.
   *
   * What about when we want to start a new one? Like, wouldn't it be better for these params to be
   * on the global object? Then again, you can still override everything that okhttp does.
   */

  /**
   * Uploads one single file, reporting progress as it goes, and returning the final state when the
   * upload completes. The worker is only responsible for doing the upload and accurately reporting
   * state/errors. Owning objects handle errors, delegate
   */
  private class Worker(
    val putUrl: Uri,
    val file: File,
    val retriesPerChunk: Int,
    val chunkSizeBytes: Int,
  ) {
    suspend fun doUpload() {
      // TODO: Callback for delivering state data
      // supervisor scopes can crash without affecting parent scope
      supervisorScope {
        // TODO: Prepare upload (Maybe by launch()ing coroutines on main thread to update the mgr etc)
        val httpResponse = withContext(Dispatchers.IO) {
          // TODO: Http
          "dummy object"
        } // withContext(Dispatchers.IO)

        val finalState = MuxVodUpload.State(0, 0)
        progress(finalState)
        finalState
      } // supervisorScope
    }

    private fun progress(state: MuxVodUpload.State)  {
    }

  }
}

/**
 * Return a new [MuxUploadInfo] with the given data overwritten. Any argument not provided will be
 * copied from the original object.
 */
@JvmSynthetic
internal fun MuxUploadInfo.update(
  remoteUri: Uri = this.remoteUri,
  file: File = this.file,
  lastKnownState: MuxVodUpload.State? = this.lastKnownState,
  chunkSize: Long = this.chunkSize,
  retriesPerChunk: Int = this.retriesPerChunk,
  retryBaseTimeMs: Long = this.retryBaseTimeMs,
  uploadJob: Deferred<MuxVodUpload.State>? = this.uploadJob,
  successCallback: ((MuxVodUpload.State) -> Unit)? = null,
  progressCallback: ((MuxVodUpload.State) -> Unit)? = null,
  errorCallback: ((Exception) -> Unit)? = null,
) = MuxUploadInfo(
  remoteUri,
  file,
  lastKnownState,
  chunkSize,
  retriesPerChunk,
  retryBaseTimeMs,
  uploadJob,
  successCallback,
  progressCallback,
  errorCallback
)
