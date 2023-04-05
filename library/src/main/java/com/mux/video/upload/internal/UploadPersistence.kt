package com.mux.video.upload.internal

import android.content.Context
import android.content.SharedPreferences
import com.mux.video.upload.api.MuxUpload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

@JvmSynthetic
internal fun initializeUploadPersistence(appContext: Context) {
  UploadPersistence.prefs = appContext.applicationContext.getSharedPreferences("mux_upload", 0)
}

@JvmSynthetic
internal fun writeUploadState(uploadInfo: UploadInfo, state: MuxUpload.State) {
  UploadPersistence.write(
    PersistenceEntry(
      file = uploadInfo.file,
      savedAtLocalMs = Date().time,
      state = if(uploadInfo.isRunning()) {
        UploadPersistence.WAS_RUNNING
      } else {
        UploadPersistence.WAS_PAUSED
      },
      lastSuccessfulByte = state.bytesUploaded - 1
    )
  )
}

@JvmSynthetic
internal fun forgetUploadState(uploadInfo: UploadInfo) {
  UploadPersistence.removeForFile(uploadInfo.file)
}

/**
 * Datastore for uploads that are paused, are running, or should be running. Internally it models
 * the store as a Map keyed by the the upload entry's file name, storing json in shared prefs.
 * Objects are cleared from this store when uploads are finished, failed, or canceled. This is
 * handled by MuxUploadManager
 */
private object UploadPersistence {
  const val WAS_RUNNING = 0
  const val WAS_PAUSED = 1
  const val LIST_KEY = "uploads"

  lateinit var prefs: SharedPreferences

  @Throws
  @Synchronized
  fun write(entry: PersistenceEntry) {
    checkInitialized()
    val entries = fetchEntries()
    entries[entry.file.absolutePath] = entry
    writeEntries(entries)
  }

  @Throws
  @Synchronized
  fun removeForFile(file: File) {
    checkInitialized()
    val entries = readEntries()
    entries -= file.absolutePath
    writeEntries(entries)
  }

  @Throws
  @Synchronized
  fun readEntries(): MutableMap<String, PersistenceEntry> {
    checkInitialized()
    return readEntries()
  }

  @Throws
  private fun writeEntries(entries: Map<String, PersistenceEntry>) {
    val entriesJson = JSONArray()
    entries.forEach { entriesJson.put(it.value.toJson()) }
    prefs.edit().putString(LIST_KEY, entriesJson.toString()).apply()
  }

  @Throws
  private fun fetchEntries(): MutableMap<String, PersistenceEntry> {
    val jsonStr = prefs.getString(LIST_KEY, null)
    return if (jsonStr == null) {
      mutableMapOf()
    } else {
      val jsonArray = JSONArray(jsonStr)
      val parsedEntries = mutableMapOf<String, PersistenceEntry>()
      for (index in 0..jsonArray.length()) {
        jsonArray.getJSONObject(index)?.let { elemJson ->
          val entry = elemJson.parsePersistenceEntry()
          parsedEntries.put(entry.file.absolutePath, elemJson.parsePersistenceEntry())
        }
      }
      return parsedEntries
    }
  }

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
  val file: File,
  val savedAtLocalMs: Long,
  val state: Int,
  val lastSuccessfulByte: Long,
) {
  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("file", file.absolutePath)
      put("data", JSONObject().apply {
        put("saved_at_local_ms", savedAtLocalMs)
        put("state", state)
        put("last_successful_byte", lastSuccessfulByte)
      })
    }
  }
}

private fun JSONObject.parsePersistenceEntry(): PersistenceEntry {
  val file = File(getString("file"))
  val data = getJSONObject("data")
  return PersistenceEntry(
    file = file,
    savedAtLocalMs = data.optLong("saved_at_local_ms"),
    state = data.optInt("state"),
    lastSuccessfulByte = data.optLong("last_successful_byte")
  )
}