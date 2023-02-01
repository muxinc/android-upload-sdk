package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.api.MuxVodUpload
import kotlinx.coroutines.*
import java.io.File

/**
 * Creates a new Upload Job for this
 */
@JvmSynthetic
internal fun createUploadJob(upload: MuxVodUpload): Deferred<MuxVodUpload.State> {
  // TODO: Callback delivers state data
  return UploadJobFactory.createUploadJob(CoroutineScope(Dispatchers.Default))
}

/**
 * TODO: Doc
 */
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
    val progress: (MuxVodUpload.State) -> Unit
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
  }
}
