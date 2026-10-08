package com.mux.video.upload.internal

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.mux.video.upload.api.HdrHandling
import com.mux.video.upload.api.MuxUpload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*
import java.security.MessageDigest

@JvmSynthetic
internal fun initializeUploadPersistence(appContext: Context) {
  UploadPersistence.prefs = appContext.applicationContext.getSharedPreferences("mux_upload", 0)
}

@JvmSynthetic
internal fun writeUploadState(uploadInfo: UploadInfo, state: MuxUpload.Progress) {
  UploadPersistence.write(
    UploadEntry(
      file = uploadInfo.inputFile,
      url = uploadInfo.remoteUri.toString(),
      savedAtLocalMs = Date().time,
      state = if (uploadInfo.isRunning()) {
        UploadPersistence.WAS_RUNNING
      } else {
        UploadPersistence.WAS_PAUSED
      },
      bytesSent = state.bytesUploaded,
      chunkSize = uploadInfo.chunkSize,
      retriesPerChunk = uploadInfo.retriesPerChunk,
      optOut = uploadInfo.optOut,
      inputStandardization = uploadInfo.inputStandardization,
      originalSelected = uploadInfo.attempt?.preparation?.originalSelected ?: true,
      attemptId = uploadInfo.attempt?.id,
      generatedResumeBlocked = uploadInfo.attempt?.preparation?.generatedRequestStarted == true || uploadInfo.generatedResumeBlocked,
    )
  )
}

internal data class UploadResumeState(
  val bytesSent: Long = 0,
  val originalSelected: Boolean = false,
  val generatedResumeBlocked: Boolean = false,
  val paused: Boolean = false,
  val attemptId: String? = null,
)

internal fun readUploadResumeState(upload: UploadInfo): UploadResumeState = UploadPersistence.readState(upload)

@JvmSynthetic
internal fun readLastByteForFile(upload: UploadInfo): Long = readUploadResumeState(upload).let {
  if (it.generatedResumeBlocked) 0 else it.bytesSent
}

@JvmSynthetic
internal fun forgetUploadState(uploadInfo: UploadInfo) {
  UploadPersistence.removeForFile(uploadInfo)
}

@JvmSynthetic
internal fun readAllCachedUploads(includePaused: Boolean = true): List<UploadInfo> {
  return UploadPersistence.readEntries()
    .map { it.value }
    .filter { includePaused || it.state == UploadPersistence.WAS_RUNNING }
    .map {
      UploadInfo(
        inputStandardization = it.inputStandardization,
        remoteUri = Uri.parse(it.url),
        inputFile =  it.file,
        chunkSize = it.chunkSize,
        retriesPerChunk = it.retriesPerChunk,
        optOut = it.optOut,
        uploadJob = null,
        statusFlow = null,
        restoredFromOriginal = it.originalSelected,
        generatedResumeBlocked = it.generatedResumeBlocked,
      )
  }
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
  const val BLOCKS_KEY = "generated_upload_blocks"

  lateinit var prefs: SharedPreferences

  @Throws
  @Synchronized
  fun write(entry: UploadEntry) {
    checkInitialized()
    val entries = fetchEntries()
    entries[entry.file.absolutePath] = entry
    val blocks = readBlocks()
    if (entry.generatedResumeBlocked) blocks += destinationKey(entry.url)
    writeEntries(entries, blocks)
  }

  @Synchronized
  fun readState(upload: UploadInfo): UploadResumeState {
    checkInitialized()
    val entry = fetchEntries()[upload.inputFile.absolutePath]?.takeIf { it.url == upload.remoteUri.toString() }
    return UploadResumeState(
      bytesSent = entry?.bytesSent ?: 0,
      originalSelected = entry?.originalSelected ?: false,
      generatedResumeBlocked = entry?.generatedResumeBlocked == true ||
        destinationKey(upload.remoteUri.toString()) in readBlocks(),
      paused = entry?.state == WAS_PAUSED,
      attemptId = entry?.attemptId,
    )
  }

  @Throws
  @Synchronized
  fun removeForFile(upload: UploadInfo) {
    checkInitialized()
    val entries = fetchEntries()
    val entry = entries[upload.inputFile.absolutePath] ?: return
    if (entry.url != upload.remoteUri.toString() ||
      (upload.attempt != null && entry.attemptId != null && entry.attemptId != upload.attempt.id &&
        entry.attemptId != upload.attempt.previousPersistenceOwnerId)) return
    val blocks = readBlocks()
    // Cancelling or replacing a handle cannot undo bytes already sent to this destination.
    if (entry.generatedResumeBlocked) blocks += destinationKey(entry.url)
    entries -= upload.inputFile.absolutePath
    writeEntries(entries, blocks)
  }

  @Throws
  @Synchronized
  fun readEntries(): MutableMap<String, UploadEntry> {
    checkInitialized()
    return fetchEntries()
  }

  @Throws
  @Synchronized
  private fun writeEntries(entries: Map<String, UploadEntry>, blocks: Set<String>) {
    val entriesJson = JSONArray()
    entries.forEach { entriesJson.put(it.value.toJson()) }
    prefs.edit().putString(LIST_KEY, entriesJson.toString())
      .putString(BLOCKS_KEY, JSONArray(blocks.toList()).toString()).apply()
  }

  @Throws
  @Synchronized
  private fun fetchEntries(): MutableMap<String, UploadEntry> {
    val jsonStr = prefs.getString(LIST_KEY, null)
    return if (jsonStr == null) {
      mutableMapOf()
    } else {
      val jsonArray = JSONArray(jsonStr)
      if (jsonArray.length() <= 0) {
        return mutableMapOf()
      } else {
        val parsedEntries = mutableMapOf<String, UploadEntry>()
        for (index in 0 until jsonArray.length()) {
          jsonArray.getJSONObject(index)?.let { elemJson ->
            val entry = elemJson.parsePersistenceEntry()
            parsedEntries[entry.file.absolutePath] = entry
          }
        }
        return parsedEntries
      }
    }
  }

  private fun destinationKey(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

  private fun readBlocks(): MutableSet<String> {
    val json = JSONArray(prefs.getString(BLOCKS_KEY, null) ?: "[]")
    return (0 until json.length()).mapTo(mutableSetOf()) { json.getString(it) }
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

private data class UploadEntry(
  val file: File,
  val url: String,
  val chunkSize: Int,
  val retriesPerChunk: Int,
  val optOut: Boolean,
  val savedAtLocalMs: Long,
  val state: Int,
  val bytesSent: Long,
  val inputStandardization: InputStandardization,
  val generatedResumeBlocked: Boolean = false,
  val originalSelected: Boolean = true,
  val attemptId: String? = null,
) {
  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("file", file.absolutePath)
      put("data", JSONObject().apply {
        put("generated_resume_blocked", generatedResumeBlocked)
        put("original_selected", originalSelected)
        put("attempt_id", attemptId)
        put("url", url)
        put("chunk_size", chunkSize)
        put("retries_per_chunk", retriesPerChunk)
        put("opt_out", optOut)
        put("saved_at_local_ms", savedAtLocalMs)
        put("state", state)
        put("bytes_sent", bytesSent)
        put("input_standardization", JSONObject().apply {
          put("requested", inputStandardization.standardizationRequested)
          put("maximum_resolution", inputStandardization.maximumResolution.name)
          put("hdr_handling", inputStandardization.hdrHandling.name)
        })
      })
    }
  }
}

private fun JSONObject.parsePersistenceEntry(): UploadEntry {
  val file = File(getString("file"))
  val data = getJSONObject("data")
  return UploadEntry(
    file = file,
    chunkSize = data.optInt("chunk_size"),
    url = data.optString("url"),
    retriesPerChunk = data.optInt("retries_per_chunk"),
    optOut = data.optBoolean("opt_out"),
    savedAtLocalMs = data.optLong("saved_at_local_ms"),
    state = data.optInt("state"),
    bytesSent = data.optLong("bytes_sent"),
    generatedResumeBlocked = data.optBoolean("generated_resume_blocked", false),
    originalSelected = data.optBoolean("original_selected", true),
    attemptId = data.optString("attempt_id").takeIf { it.isNotEmpty() },
    inputStandardization = data.optJSONObject("input_standardization").let { options ->
      InputStandardization(
        standardizationRequested = options?.optBoolean("requested", true) ?: true,
        maximumResolution = MaximumResolution.entries.find {
          it.name == options?.optString("maximum_resolution")
        } ?: MaximumResolution.Default,
        hdrHandling = HdrHandling.entries.find {
          it.name == options?.optString("hdr_handling")
        } ?: HdrHandling.Preserve,
      )
    },
  )
}
