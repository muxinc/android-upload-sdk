package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.api.MuxVodUpload
import kotlinx.coroutines.Deferred
import java.io.File

/**
 * Represents a Mux Video direct upload, which may be in-progress, paused, finished, or not yet
 * started. The state (as reported at the time the object was created) can be read from
 * [lastKnownState], but it may be stale. To get an up-to-date version of this object, use
 */
internal data class MuxUploadInfo(
  val remoteUri: Uri,
  val file: File,
  val lastKnownState: MuxVodUpload.State?,
  val chunkSize: Long,
  val retriesPerChunk: Int,
  val retryBaseTimeMs: Long,
  @JvmSynthetic internal val uploadJob: Deferred<MuxVodUpload.State>?
)

@JvmSynthetic
internal fun MuxUploadInfo.update(
  remoteUri: Uri = this.remoteUri,
  file: File = this.file,
  lastKnownState: MuxVodUpload.State? = this.lastKnownState,
  chunkSize: Long = this.chunkSize,
  retriesPerChunk: Int = this.retriesPerChunk,
  retryBaseTimeMs: Long = this.retryBaseTimeMs,
  uploadJob: Deferred<MuxVodUpload.State>? = this.uploadJob
) = MuxUploadInfo(
  remoteUri,
  file,
  lastKnownState,
  chunkSize,
  retriesPerChunk,
  retryBaseTimeMs,
  uploadJob
)
