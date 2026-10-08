package com.mux.video.upload.internal

import org.json.JSONObject

internal enum class PreparationPhase { Preparing, Validated, Uploading }

/** The durable record is authoritative; preference progress is only a UI hint. */
internal data class GeneratedResumeState(
  val source: PayloadIdentity,
  val phase: PreparationPhase = PreparationPhase.Preparing,
  val ownedPath: String? = null,
  val payload: PayloadIdentity? = null,
  val networkStarted: Boolean = false,
  val abandoned: Boolean = false,
) {
  fun toJson() = JSONObject().put("source", source.toJson()).put("phase", phase.name)
    .put("owned_path", ownedPath).put("payload", payload?.toJson()).put("network_started", networkStarted).put("abandoned", abandoned)
  companion object {
    fun fromJson(json: JSONObject) = GeneratedResumeState(
      source = PayloadIdentity.fromJson(json.getJSONObject("source")),
      phase = PreparationPhase.valueOf(json.getString("phase")),
      ownedPath = json.optString("owned_path").takeIf { it.isNotEmpty() },
      payload = json.optJSONObject("payload")?.let(PayloadIdentity::fromJson),
      networkStarted = json.getBoolean("network_started"),
      abandoned = json.optBoolean("abandoned", false),
    ).also {
      require(it.payload == null || it.payload.path == it.ownedPath)
      require(it.phase == PreparationPhase.Preparing || it.payload != null)
    }
  }
}
