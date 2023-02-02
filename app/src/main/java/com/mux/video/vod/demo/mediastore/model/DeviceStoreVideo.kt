package com.mux.video.vod.demo.mediastore.model

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Represents a video saved on the device, accessed via a ContentResolver. To use with the SDK, we
 * need to copy the file to our local storage
 */
data class DeviceStoreVideo (
  val title: String,
  val file: File,
  val fromApp: String,
  )