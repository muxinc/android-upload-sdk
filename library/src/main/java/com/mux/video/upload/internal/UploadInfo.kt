package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.api.MuxUpload
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * This object is the SDK's internal representation of an upload that is in-progress. The public
 * object is [MuxUpload], which is backed by an instance of this object.
 *
 * This object is immutable. To create an updated version use [update]. The Upload Manager can
 * update the internal state of its jobs based on the content of this object
 */
internal data class UploadInfo(
  @JvmSynthetic internal val remoteUri: Uri,
  @JvmSynthetic internal val file: File,
  @JvmSynthetic internal val videoMimeType: String,
  @JvmSynthetic internal val chunkSize: Int,
  @JvmSynthetic internal val retriesPerChunk: Int,
  @JvmSynthetic internal val retryBaseTimeMs: Long,
  @JvmSynthetic internal val optOut: Boolean,
  @JvmSynthetic internal val uploadJob: Deferred<Result<MuxUpload.Progress>>?,
  @JvmSynthetic internal val successChannel: SharedFlow<MuxUpload.Progress>?,
  @JvmSynthetic internal val progressChannel: SharedFlow<MuxUpload.Progress>?,
  @JvmSynthetic internal val errorChannel: SharedFlow<Exception>?,
) {
  fun isRunning(): Boolean = uploadJob?.isActive ?: false
}

/**
 * Return a new [UploadInfo] with the given data overwritten. Any argument not provided will be
 * copied from the original object.
 */
@JvmSynthetic
internal fun UploadInfo.update(
  remoteUri: Uri = this.remoteUri,
  file: File = this.file,
  videoMimeType: String = this.videoMimeType,
  chunkSize: Int = this.chunkSize,
  retriesPerChunk: Int = this.retriesPerChunk,
  retryBaseTimeMs: Long = this.retryBaseTimeMs,
  optOut: Boolean = this.optOut,
  uploadJob: Deferred<Result<MuxUpload.Progress>>? = this.uploadJob,
  successChannel: SharedFlow<MuxUpload.Progress>? = this.successChannel,
  progressChannel: SharedFlow<MuxUpload.Progress>? = this.progressChannel,
  errorChannel: SharedFlow<Exception>? = this.errorChannel,
) = UploadInfo(
  remoteUri,
  file,
  videoMimeType,
  chunkSize,
  retriesPerChunk,
  retryBaseTimeMs,
  optOut,
  uploadJob,
  successChannel,
  progressChannel,
  errorChannel
)
