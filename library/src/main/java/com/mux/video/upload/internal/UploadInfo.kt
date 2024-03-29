package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.api.UploadStatus
import com.mux.video.upload.api.MuxUpload
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * This object is the SDK's internal representation of an upload that is in-progress. The public
 * object is [MuxUpload], which is backed by an instance of this object.
 *
 * This object is immutable. To create an updated version use [update]. The Upload Manager can
 * update the internal state of its jobs based on the content of this object
 *
 * To create a new upload job, use [UploadJobFactory.create]. The UploadInfo returned will have a
 * Job and Flows populated
 */
internal data class UploadInfo(
  @JvmSynthetic internal val standardizationRequested: Boolean = true,
  @JvmSynthetic internal val remoteUri: Uri,
  @JvmSynthetic internal val inputFile: File,
  @JvmSynthetic internal val standardizedFile: File? = null,
  @JvmSynthetic internal val chunkSize: Int,
  @JvmSynthetic internal val retriesPerChunk: Int,
  @JvmSynthetic internal val optOut: Boolean,
  @JvmSynthetic internal val uploadJob: Deferred<Result<UploadStatus>>?,
  @JvmSynthetic internal val statusFlow: StateFlow<UploadStatus>?,
) {
  fun isRunning(): Boolean = uploadJob?.isActive ?: false
}

/**
 * Return a new [UploadInfo] with the given data overwritten. Any argument not provided will be
 * copied from the original object.
 */
@JvmSynthetic
internal fun UploadInfo.update(
  standardizationRequested: Boolean = this.standardizationRequested,
  remoteUri: Uri = this.remoteUri,
  file: File = this.inputFile,
  standardizedFile: File? = this.standardizedFile,
  chunkSize: Int = this.chunkSize,
  retriesPerChunk: Int = this.retriesPerChunk,
  optOut: Boolean = this.optOut,
  uploadJob: Deferred<Result<UploadStatus>>? = this.uploadJob,
  statusFlow: StateFlow<UploadStatus>? = this.statusFlow,
) = UploadInfo(
  standardizationRequested,
  remoteUri,
  file,
  standardizedFile,
  chunkSize,
  retriesPerChunk,
  optOut,
  uploadJob,
  statusFlow,
)
