package com.mux.video.upload.internal

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Bounded first/last 64 KiB fingerprint, with path and stat checks around the read. */
internal data class PayloadIdentity(val path: String, val size: Long, val modified: Long, val edgeHash: String) {
  fun matches(file: File): Boolean = this == capture(file)
  fun toJson() = JSONObject().put("path", path).put("size", size).put("mtime", modified).put("edge_hash", edgeHash)

  companion object {
    fun fromJson(json: JSONObject) = PayloadIdentity(json.getString("path"), json.getLong("size"),
      json.getLong("mtime"), json.getString("edge_hash")).also {
      require(it.size > 0 && it.modified >= 0 && it.edgeHash.matches(Regex("[0-9a-f]{64}")))
    }
    fun capture(file: File): PayloadIdentity? = try {
      val path = file.canonicalPath
      val size = file.length()
      val modified = file.lastModified()
      if (!file.isFile || !file.canRead() || size <= 0) null else {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
          val first = ByteArray(minOf(size, 64 * 1024L).toInt())
          input.readFully(first); digest.update(first)
          if (size > first.size) {
            val last = ByteArray(minOf(size - first.size, 64 * 1024L).toInt())
            input.seek(size - last.size); input.readFully(last); digest.update(last)
          }
        }
        if (file.canonicalPath != path || file.length() != size || file.lastModified() != modified) null
        else PayloadIdentity(path, size, modified, digest.digest().joinToString("") { "%02x".format(it) })
      }
    } catch (_: Exception) { null }
  }
}
