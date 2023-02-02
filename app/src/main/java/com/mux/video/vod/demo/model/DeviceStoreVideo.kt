package com.mux.video.vod.demo.model

import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Represents a video saved on the device, accessed via a ContentResolver. To use with the SDK, we
 * need to copy the file to our local storage
 */
data class DeviceStoreVideo (
  val contentUri: Uri,
  val title: String,
  val fileDescriptor: ParcelFileDescriptor
  )