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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@JvmSynthetic
internal fun initializeUploadPersistence(appContext: Context) {
  UploadPersistence.prefs = appContext.applicationContext.getSharedPreferences("mux_upload", 0)
  UploadPersistence.reconcileHiddenStandardized()
  UploadPersistence.scheduleBlockedCleanup(appContext)
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
      standardizedResumeBlocked = uploadInfo.standardizedResumeBlocked,
    )
  )
}

internal data class UploadResumeState(
  val bytesSent: Long = 0,
  val originalSelected: Boolean = false,
  val standardizedResumeBlocked: Boolean = false,
  val paused: Boolean = false,
  val attemptId: String? = null,
  val standardized: StandardizedResumeState? = null,
  val standardizedOptions: InputStandardization? = null,
)

internal fun readUploadResumeState(upload: UploadInfo): UploadResumeState = UploadPersistence.readState(upload)

@JvmSynthetic
internal fun readLastByteForFile(upload: UploadInfo): Long = readUploadResumeState(upload).let {
  if (it.standardizedResumeBlocked) 0 else it.bytesSent
}

@JvmSynthetic
internal fun forgetUploadState(uploadInfo: UploadInfo) {
  if (uploadInfo.attempt?.isRestarting() == true) {
    // Replace the attempt, retaining its selected file and last acknowledged byte.
    writeUploadState(uploadInfo, uploadInfo.attempt.confirmedProgress())
    return
  }
  UploadPersistence.hideStandardized(uploadInfo)
  UploadPersistence.removeForFile(uploadInfo)
  UploadPersistence.scheduleStandardizedRetirement(uploadInfo)
}

internal data class CachedUpload(val upload: UploadInfo, val resumeState: UploadResumeState)

internal fun readCachedUploadSnapshots(): List<CachedUpload> = UploadPersistence.readSnapshots()

@JvmSynthetic
internal fun readAllCachedUploads(): List<UploadInfo> = readCachedUploadSnapshots().map { it.upload }

private fun UploadEntry.toUploadInfo(blocked: Boolean) = UploadInfo(
  inputStandardization = inputStandardization,
  remoteUri = Uri.parse(url),
  inputFile = file,
  chunkSize = chunkSize,
  retriesPerChunk = retriesPerChunk,
  optOut = optOut,
  uploadJob = null,
  statusFlow = null,
  restoredFromOriginal = originalSelected,
  standardizedResumeBlocked = blocked,
)

/**
 * Datastore for uploads that are paused, are running, or should be running. Internally it models
 * the store as a Map keyed by the the upload entry's file name, storing json in shared prefs.
 * Objects are cleared from this store when uploads are finished, failed, or canceled. This is
 * handled by MuxUploadManager
 */
internal object UploadPersistence {
  const val WAS_RUNNING = 0
  const val WAS_PAUSED = 1
  const val LIST_KEY = "uploads"
  const val BLOCKS_KEY = "generated_upload_blocks"
  // Keep the persisted keys compatible with existing saved uploads.
  private const val STANDARDIZED_KEY = "generated_payloads_v1"
  private const val STANDARDIZED_BLOCKS_KEY = "generated_destination_blocks_v1"
  // Only IO/export threads acquire this lock; UI pause/cancel never waits for commit().
  private val processIdentity = UUID.randomUUID().toString()
  private val standardizedWriteLock = Any()
  private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun reconcileHiddenStandardized() {
    val records = readStandardized()
    hiddenStandardized.forEach { (key, owner) ->
      if (records[key]?.attemptId != owner.attemptId) hiddenStandardized.remove(key, owner)
    }
  }

  fun scheduleStandardizedRetirement(upload: UploadInfo): kotlinx.coroutines.Job {
    val ownerStore = prefs
    return cleanupScope.launch {
      try {
        upload.uploadJob?.join()
        if (prefs !== ownerStore) return@launch
        retireStandardized(upload)
      } catch (_: Exception) {
        com.mux.video.upload.MuxUploadSdk.logger.e("MuxUpload", "Standardized persistence cleanup failed")
      }
    }
  }
  private data class HiddenStandardized(val attemptId: String)
  private val hiddenStandardized = java.util.concurrent.ConcurrentHashMap<String, HiddenStandardized>()
  private fun isHidden(entry: UploadEntry) = entry.attemptId != null &&
    hiddenStandardized[destinationKey(entry.url)]?.attemptId == entry.attemptId
  private fun ownsStandardized(upload: UploadInfo, entry: UploadEntry) =
    entry.attemptId == upload.attempt?.id || entry.attemptId == upload.attempt?.previousPersistenceOwnerId
  fun hideStandardized(upload: UploadInfo) {
    val key = destinationKey(upload.remoteUri.toString())
    val entry = readStandardized()[key] ?: return
    if (ownsStandardized(upload, entry)) {
      entry.attemptId?.let { hiddenStandardized[key] = HiddenStandardized(it) }
      // Hiding a cancelled preparation must not permanently block an untouched destination.
      if (entry.standardized?.networkStarted == true || entry.standardizedResumeBlocked)
        blockDestination(entry.url)
    }
  }

  /** Only a different process may clean cancelled/blocked exports whose engine could have died. */
  fun scheduleBlockedCleanup(context: Context): kotlinx.coroutines.Job {
    if (readStandardized().values.none { it.processIdentity != processIdentity })
      return kotlinx.coroutines.Job().apply { complete() }
    val ownerStore = prefs
    return cleanupScope.launch {
      try {
        synchronized(standardizedWriteLock) {
          if (prefs !== ownerStore) return@synchronized
          val records = readStandardized()
          val standardizedBlocks = readStandardizedBlocks()
          val blocked = readBlocks() + standardizedBlocks
          val stale = records.values.filter { (destinationKey(it.url) in blocked || it.standardized?.abandoned == true) &&
            it.processIdentity != processIdentity }
          var changed = false
          for (entry in stale) {
            val path = entry.standardized?.ownedPath
            val owned = path?.let { com.mux.video.upload.internal.standardization.SdrStandardizedFile.restore(context.cacheDir, it) }
            if (path == null || owned?.delete() == true) {
              if (entry.standardized?.networkStarted == true || entry.standardizedResumeBlocked)
                standardizedBlocks += destinationKey(entry.url)
              records.remove(destinationKey(entry.url)); changed = true
            }
          }
          if (changed) prefs.edit().putString(STANDARDIZED_KEY, JSONArray(records.values.map { it.toJson() }).toString())
            .putString(STANDARDIZED_BLOCKS_KEY, JSONArray(standardizedBlocks.toList()).toString()).commit()
        }
      } catch (_: Exception) {
        com.mux.video.upload.MuxUploadSdk.logger.e("MuxUpload", "Standardized persistence cleanup failed")
      }
    }
  }

  @Synchronized private fun blockDestination(url: String) {
    val blocked = readBlocks().apply { add(destinationKey(url)) }
    prefs.edit().putString(BLOCKS_KEY, JSONArray(blocked.toList()).toString()).apply()
  }

  fun writeStandardized(upload: UploadInfo, state: StandardizedResumeState) {
    synchronized(standardizedWriteLock) {
      val records = readStandardized()
      val key = destinationKey(upload.remoteUri.toString())
      val existing = records[key]
      val attempt = upload.attempt
      // A stopped owner may only mark its existing record abandoned, never claim or revive one.
      val cancelled = attempt?.isCancelled() == true || attempt?.replacementFailure() != null
      if (attempt?.isSuperseded() == true ||
        cancelled && (!state.abandoned || existing == null || !ownsStandardized(upload, existing))) return
      if (existing != null && !ownsStandardized(upload, existing)) throw UploadResumeBlockedException()
      val entry = UploadEntry(upload.inputFile, upload.remoteUri.toString(), upload.chunkSize,
        upload.retriesPerChunk, upload.optOut, Date().time, WAS_RUNNING,
        attempt?.confirmedProgress()?.bytesUploaded ?: existing?.bytesSent ?: 0,
        upload.inputStandardization, originalSelected = false, attemptId = upload.attempt?.id,
        standardized = state, processIdentity = processIdentity)
      records[destinationKey(entry.url)] = entry
      val editor = prefs.edit().putString(STANDARDIZED_KEY, JSONArray(records.values.map { it.toJson() }).toString())
      if (!editor.commit()) throw UploadResumeBlockedException()
      hiddenStandardized[destinationKey(entry.url)]?.takeIf { it.attemptId != entry.attemptId }?.let {
        hiddenStandardized.remove(destinationKey(entry.url), it)
      }
    }
  }

  fun retireStandardized(upload: UploadInfo, block: Boolean = false) {
    synchronized(standardizedWriteLock) {
      val records = readStandardized()
      val key = destinationKey(upload.remoteUri.toString())
      val entry = records[key] ?: return
      if (!ownsStandardized(upload, entry)) return
      val blocks = readStandardizedBlocks()
      // Keep only the destination hash after terminal cleanup, not paths/URLs/identities.
      // No time-based eviction: without known URL expiry, reuse could splice different bytes.
      if (block || entry.standardized?.networkStarted == true || entry.standardizedResumeBlocked) blocks += key
      records.remove(key)
      if (!prefs.edit().putString(STANDARDIZED_KEY, JSONArray(records.values.map { it.toJson() }).toString())
          .putString(STANDARDIZED_BLOCKS_KEY, JSONArray(blocks.toList()).toString()).commit())
        throw UploadResumeBlockedException()
      hiddenStandardized[key]?.takeIf { it.attemptId == entry.attemptId }?.let { hiddenStandardized.remove(key, it) }
    }
  }

  private fun readStandardizedBlocks(): MutableSet<String> {
    val json = JSONArray(prefs.getString(STANDARDIZED_BLOCKS_KEY, null) ?: "[]")
    return (0 until json.length()).mapTo(mutableSetOf()) { json.getString(it) }
  }

  private fun readStandardized(): MutableMap<String, UploadEntry> {
    val json = JSONArray(prefs.getString(STANDARDIZED_KEY, null) ?: "[]")
    return (0 until json.length()).map { json.getJSONObject(it).parsePersistenceEntry() }
      .associateByTo(mutableMapOf()) { destinationKey(it.url) }
  }


  lateinit var prefs: SharedPreferences

  @Throws
  @Synchronized
  fun write(entry: UploadEntry) {
    checkInitialized()
    val entries = fetchEntries()
    entries[entry.file.absolutePath] = entry
    val blocks = readBlocks()
    if (entry.standardizedResumeBlocked) blocks += destinationKey(entry.url)
    writeEntries(entries, blocks)
  }

  @Synchronized
  fun readState(upload: UploadInfo): UploadResumeState {
    checkInitialized()
    val entry = fetchEntries()[upload.inputFile.absolutePath]?.takeIf { it.url == upload.remoteUri.toString() }
    val key = destinationKey(upload.remoteUri.toString())
    if (key in readBlocks() || key in readStandardizedBlocks()) return UploadResumeState(standardizedResumeBlocked = true)
    val standardized = readStandardized()[key]
    if (standardized?.let(::isHidden) == true) return UploadResumeState(attemptId = standardized?.attemptId,
      standardizedResumeBlocked = standardized?.let { it.standardized?.networkStarted == true || it.standardizedResumeBlocked } == true)
    if (standardized != null) {
      if (standardized.file.absoluteFile != upload.inputFile.absoluteFile) return UploadResumeState(standardizedResumeBlocked = true)
      return resumeState(standardized, false).copy(paused = entry?.state == WAS_PAUSED,
        bytesSent = maxOf(entry?.bytesSent ?: 0, standardized.bytesSent))
    }
    return resumeState(entry, destinationKey(upload.remoteUri.toString()) in readBlocks())
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
    if (entry.standardizedResumeBlocked) blocks += destinationKey(entry.url)
    entries -= upload.inputFile.absolutePath
    writeEntries(entries, blocks)
  }

  @Synchronized
  fun readSnapshots(): List<CachedUpload> {
    checkInitialized()
    val blocks = readBlocks() + readStandardizedBlocks()
    val entries = fetchEntries()
    val standardized = readStandardized()
    val hints = entries.toMap()
    standardized.values.filter { !isHidden(it) && it.standardized?.abandoned != true && destinationKey(it.url) !in blocks }
      .groupBy { it.file.absolutePath }.values.forEach { candidates ->
        val entry = candidates.maxBy { it.savedAtLocalMs }
        val hint = hints[entry.file.absolutePath]
        // Merge once per source, using the original hint snapshot. An older destination's
        // durable record must not erase the current destination's pause/progress hint.
        if (hint == null || hint.url == entry.url || entry.savedAtLocalMs > hint.savedAtLocalMs) {
          val matching = hint?.takeIf { it.url == entry.url }
          entries[entry.file.absolutePath] = entry.copy(state = matching?.state ?: entry.state,
            bytesSent = maxOf(matching?.bytesSent ?: 0, entry.bytesSent))
        }
      }
    return entries.values.filter { standardized[destinationKey(it.url)]?.let { record ->
      isHidden(record) || record.standardized?.abandoned == true } != true }.map { entry ->
      val record = standardized[destinationKey(entry.url)]
      val blocked = destinationKey(entry.url) in blocks ||
        (record != null && record.file.absoluteFile != entry.file.absoluteFile)
      val saved = resumeState(entry, blocked)
      CachedUpload(entry.toUploadInfo(saved.standardizedResumeBlocked), saved)
    }
  }

  private fun resumeState(entry: UploadEntry?, blocked: Boolean) = UploadResumeState(
    bytesSent = entry?.bytesSent ?: 0,
    originalSelected = entry?.originalSelected ?: false,
    standardizedResumeBlocked = entry?.standardizedResumeBlocked == true || blocked,
    paused = entry?.state == WAS_PAUSED,
    attemptId = entry?.attemptId,
    standardized = entry?.standardized,
    standardizedOptions = entry?.inputStandardization?.takeIf { entry.standardized != null },
  )

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

internal data class UploadEntry(
  val file: File,
  val url: String,
  val chunkSize: Int,
  val retriesPerChunk: Int,
  val optOut: Boolean,
  val savedAtLocalMs: Long,
  val state: Int,
  val bytesSent: Long,
  val inputStandardization: InputStandardization,
  val standardizedResumeBlocked: Boolean = false,
  val originalSelected: Boolean = true,
  val attemptId: String? = null,
  val standardized: StandardizedResumeState? = null,
  val processIdentity: String? = null,
) {
  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("file", file.absolutePath)
      put("data", JSONObject().apply {
        put("process_identity", processIdentity)
        put("generated", standardized?.toJson())
        put("generated_resume_blocked", standardizedResumeBlocked)
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
    processIdentity = data.optString("process_identity").takeIf { it.isNotEmpty() },
    standardized = data.optJSONObject("generated")?.let { runCatching { StandardizedResumeState.fromJson(it) }.getOrNull() },
    standardizedResumeBlocked = data.optBoolean("generated_resume_blocked", false) ||
      (data.has("generated") && data.optJSONObject("generated")?.let {
        runCatching { StandardizedResumeState.fromJson(it) }.isFailure } == true),
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
