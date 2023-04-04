package com.mux.video.upload.internal

import android.content.Context
import android.content.SharedPreferences

@JvmSynthetic
internal fun initializeUploadPersistence(appContext: Context) {
  UploadPersistence.prefs = appContext.applicationContext.getSharedPreferences("mux_upload", 0)
}

private object UploadPersistence {
  lateinit var prefs: SharedPreferences

  fun checkInitialized() {
    if (!this::prefs.isInitialized) {
      throw IllegalStateException("UploadPersistence wasn't initialized." +
              " Have you called MuxUploadSdk.initialize(Context)?")
    }
  }
}
