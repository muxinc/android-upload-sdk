package com.mux.video.upload.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Looper
import com.mux.video.upload.MuxUploadSdk

/**
 * Asserts that we are on the main thread, crashing if not.
 *
 * Do not use this method in public-facing API calls. It is for internal use only
 */
@JvmSynthetic
@Throws
internal fun assertMainThread() {
  if (Looper.myLooper()?.equals(Looper.getMainLooper()) != true) {
    throw IllegalStateException("This can only be called from the main thread")
  }
}

/**
 * Gets an Integer from a [MediaFormat] with the given key. If the key is missing or null, returns
 * a default value
 *
 * On API < Q, this method must branch to safely check for the key
 */
@JvmSynthetic
internal fun MediaFormat.getIntegerCompat(key: String, default: Int): Int {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    getInteger(key, default)
    // Before Q, we have to check for the key manually. Not checking can result in an exception
  } else if (containsKey(key)) {
    getInteger(key)
  } else {
    default
  }
}

/**
 * Gets a Long from a [MediaFormat] with the given key. If the key is missing or null, returns
 * a default value
 *
 * On API < Q, this method must branch to safely check for the key
 */
@JvmSynthetic
internal fun MediaFormat.getLongCompat(key: String, default: Long): Long {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    getLong(key, default)
  } else if (containsKey(key)) {
    getLong(key)
  } else {
    default
  }
}

/**
 * Returns true if the codec described is hardware-accelerated, or else it returns false.
 *
 * On API < Q, this information is not available so this method returns true. This behavior could
 * potentially be expanded in the future
 */
internal val MediaCodecInfo.isHardwareAcceleratedCompat: Boolean get() {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    isHardwareAccelerated
  } else {
    // On < Q, we can't tell. Return true because there's no point in filtering codecs in this case
    true
  }
}

/**
 * Safely dispose of a MediaCodec, stopping it first unless requested otherwise
 * If an exception is thrown during disposal, it will be logged and swallowed
 *
 * @param stop Stop the codec before releasing it. Default is true
 */
@JvmSynthetic
internal fun MediaCodec.safeDispose(stop: Boolean = true) {
  try {
    if (stop) {
      stop()
    }
    release()
  } catch (e: Exception) {
    MuxUploadSdk.logger.w("TranscoderContext", "Failed to dispose of MediaCodec", e)
  }
}

/**
 * Safely dispose of a MediaMuxer. Stopps it first unless requested otherwise (on API 18+)
 * If an exception is thrown during disposal, it will be logged and swallowed
 *
 * @param stop Stop the codec before releasing it. Default is true
 */
internal fun MediaMuxer.safeDispose(stop: Boolean = true) {
  try {
    if (stop && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      stop()
      release()
    }
  } catch (e: Exception) {
    MuxUploadSdk.logger.w("TranscoderContext", "Failed to dispose of MediaCodec", e)
  }
}

/**
 * Safely dispose of a MediaMuxer. Stopps it first unless requested otherwise (on API 18+)
 * If an exception is thrown during disposal, it will be logged and swallowed
 *
 * @param stop Stop the codec before releasing it. Default is true
 */
internal fun MediaExtractor.safeDispose(stop: Boolean = true) {
  try {
    release()
  } catch (e: Exception) {
    MuxUploadSdk.logger.w("TranscoderContext", "Failed to dispose of MediaCodec", e)
  }
}
