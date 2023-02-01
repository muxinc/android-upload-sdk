package com.mux.video.upload.api

import androidx.annotation.MainThread
import com.mux.video.upload.internal.assertMainThread
import java.util.Collections.addAll

object MuxVodUploadManager {

  /**
   * TODO: Add doc symbols
   * The list of uploads currently in-progress in the app. This list doesn't contain uploads that
   * are paused, only ones that are either uploading or queued to be uploaded
   */
  val uploadsInProgress: List<MuxVodUpload>
    get() = listOf<MuxVodUpload>().apply { addAll( _currentUploads) }
  private val _currentUploads: MutableList<MuxVodUpload> = mutableListOf()

  // TODO: internal disk-based cache for storing download state after process death
  //  For this prototype, use shared prefs and just store the state as PAUSED|UPLOADING|ERROR|DONE
  // TODO: Methods for: accessing failed uploads, retrying uploads

  /**
   * Adds a new job to this manager.
   * If it's not started, it will be started
   * If it is started, it will be restarted with new parameters
   */
  @JvmSynthetic
  @MainThread
  internal fun jobStarted(upload: MuxVodUpload) {
    // TODO: Don't use MuxVodUpload directly for this
    // TODO: Return/track a Job (or something) for callers to track this progress themselves.
    assertMainThread()
    _currentUploads += upload
  }

  @JvmSynthetic
  @MainThread
  internal fun jobCanceled(upload: MuxVodUpload) {
    // TODO: Return/track a Job (or something) for callers to track this progress themselves.
    // TODO: Don't use MuxVodUpload directly for this
    assertMainThread()
    _currentUploads -= upload
  }

  @JvmSynthetic
  @MainThread
  internal fun jobFinished(upload: MuxVodUpload) {
    // TODO: Return/track a Job (or something) for callers to track this progress themselves.
    // TODO: Don't use MuxVodUpload directly for this
    assertMainThread()
    _currentUploads -= upload
  }
}
