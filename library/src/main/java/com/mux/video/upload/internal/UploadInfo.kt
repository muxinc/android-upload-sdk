package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.api.MuxUpload
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import java.io.File

/**
 * This object is the SDK's internal representation of an upload that is in-progress. The public
 * object is [MuxUpload], which is backed by an instance of this object.
 *
 * This object is immutable. To create an updated version use [update]. The Upload Manager can
 * update the internal state of its jobs based on the content of this object
 */
internal data class UploadInfo(
  val remoteUri: Uri,
  val file: File,
  val videoMimeType: String,
  val chunkSize: Int,
  val retriesPerChunk: Int,
  val retryBaseTimeMs: Long,
  @JvmSynthetic internal val uploadJob: Deferred<Result<MuxUpload.State>>?,
  @JvmSynthetic internal val successChannel: Channel<MuxUpload.State>?,
  @JvmSynthetic internal val progressChannel: Channel<MuxUpload.State>?,
  @JvmSynthetic internal val errorChannel: Channel<Exception>?,
)

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
  uploadJob: Deferred<Result<MuxUpload.State>>? = this.uploadJob,
  successChannel: Channel<MuxUpload.State>? = this.successChannel,
  progressChannel: Channel<MuxUpload.State>? = this.progressChannel,
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
