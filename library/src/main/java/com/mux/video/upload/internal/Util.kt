package com.mux.video.upload.internal

import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * Asserts that we are on the main thread, crashing if not.
 *
 * Do not use this method in public-facing API calls. It is for internal use only
 */
@JvmSynthetic
@Throws
internal fun assertMainThread() {
  if(Looper.myLooper()?.equals(Looper.getMainLooper()) != true) {
    throw IllegalStateException("This can only be called from the main thread")
  }
}
