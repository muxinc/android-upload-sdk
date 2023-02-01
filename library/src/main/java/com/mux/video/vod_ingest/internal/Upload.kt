package com.mux.video.vod_ingest.internal

import com.mux.video.vod_ingest.api.MuxVodUpload
import kotlinx.coroutines.*

/**
 * Creates a new Upload Job for this
 */
@JvmSynthetic
internal fun createUploadJob(upload: MuxVodUpload): Job {
  return UploadJobFactory.createUploadJob(CoroutineScope(Dispatchers.Default))
}

/**
 * TODO: Doc
 */
private object UploadJobFactory {
  // TODO: Result class for type param
  fun createUploadJob(outerScope: CoroutineScope): Deferred<String> = outerScope.async {
    // supervisor scopes can crash without crashing parent scope
    supervisorScope {
      // TODO: Prepare upload (Maybe by launch()ing coroutines on main thread to update the mgr etc)
      val httpResponse = withContext(Dispatchers.IO) {
        "dummy object"
      }
      httpResponse // TODO: Wrap in Result object
    }
  }
}