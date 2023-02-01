package com.mux.video.vod_ingest.internal

import com.mux.video.vod_ingest.api.MuxVodUpload
import kotlinx.coroutines.*

/**
 * Creates a new Upload Job for this
 */
@JvmSynthetic
internal fun createUploadJob(upload: MuxVodUpload): Job {
  // TODO: Callback delivers state data
  return UploadJobFactory.createUploadJob(CoroutineScope(Dispatchers.Default))
}

/**
 * TODO: Doc
 */
private object UploadJobFactory {
  // TODO: Result class for type param
  fun createUploadJob(outerScope: CoroutineScope): Deferred<String> = outerScope.async {
    // TODO: Callback for delivering state data
    // supervisor scopes can crash without affecting parent scope
    supervisorScope {
      // TODO: Prepare upload (Maybe by launch()ing coroutines on main thread to update the mgr etc)
      val httpResponse = withContext(Dispatchers.IO) {
        // TODO: Http
        "dummy object"
      }
      httpResponse // TODO: Wrap in Result object
    }
  }
}