package com.mux.video.upload.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.IntDef
import org.json.JSONObject

@JvmSynthetic
internal fun initializeUploadPersistence(appContext: Context) {
  UploadPersistence.prefs = appContext.applicationContext.getSharedPreferences("mux_upload", 0)
}

private object UploadPersistence {
  const val WAS_RUNNING = 0
  const val WAS_PAUSED = 1

  lateinit var prefs: SharedPreferences

  private fun checkInitialized() {
    if (!this::prefs.isInitialized) {
      throw IllegalStateException(
        "UploadPersistence wasn't initialized." +
                " Have you called MuxUploadSdk.initialize(Context)?"
      )
    }
  }
}

private data class PersistenceEntry(
  val savedAtLocalMs: Long,
  val state: Int,
  val lastSuccessfulByte: Long,
) {
  fun toJson(): String {
    return JSONObject().apply {
      put("saved_at_local_ms", savedAtLocalMs)
      put("state", state)
      put("last_successful_byte", lastSuccessfulByte)
    }.toString()
  }
}

private fun String.parsePersistenceEntry(): PersistenceEntry {
  val json = JSONObject(this)
  return PersistenceEntry(
    savedAtLocalMs = json.optLong("saved_at_local_ms"),
    state = json.optInt("state"),
    lastSuccessfulByte = json.optLong("last_successful_byte")
  )
}
