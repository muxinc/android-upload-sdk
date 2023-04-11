package com.mux.video.upload.internal

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import com.mux.video.upload.BuildConfig
import com.mux.video.upload.MuxUploadSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*

internal class UploadMetrics private constructor() {

  suspend fun reportUpload(
    startTimeMillis: Long,
    endTimeMillis: Long,
    uploadInfo: UploadInfo
  ) {
    val videoDuration = withContext(Dispatchers.IO) {
      try {
        MediaMetadataRetriever().use { retriever ->
          retriever.setDataSource(uploadInfo.file.absolutePath)
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt()
        }
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("UploadMetrics", "Failed to get video duration", e)
        null
      }
    }

    val eventJson = UploadEvent(
      startTimeMillis = startTimeMillis,
      endTimeMillis = endTimeMillis,
      fileSize = uploadInfo.file.length(),
      videoDuration = videoDuration ?: 0,
      sdkVersion = BuildConfig.LIB_VERSION,
      osName = "Android",
      osVersion = Build.VERSION.RELEASE,
      deviceModel = Build.MODEL,
      appName = appName,
      appVersion = appVersion,
      regionCode = Locale.getDefault().country
    ).toJson()

    // For this case, redirect-following is desired
    val httpClient = MuxUploadSdk.httpClient().newBuilder()
      .followRedirects(true)
      .followSslRedirects(true)
      .build()
    val request = Request.Builder()
      .url("https://mobile.muxanalytics.com")
      .method("POST", eventJson.toRequestBody("application/json".toMediaType()))
      .build()

    // The HTTP Client will log if this fails or succeeds
    withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
  }

  companion object {
    // Set by initialize()
    private lateinit var appName: String
    private lateinit var appVersion: String

    @JvmSynthetic
    internal fun create() = UploadMetrics()

    @JvmSynthetic
    internal fun initialize(appContext: Context) {
      val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        appContext.packageManager.getPackageInfo(
          appContext.packageName,
          PackageManager.PackageInfoFlags.of(0)
        )
      } else {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
      }
      appName = packageInfo.packageName
      appVersion = packageInfo.versionName
    }
  }
}

private data class UploadEvent(
  // Video-Specific
  val startTimeMillis: Long,
  val endTimeMillis: Long,
  val fileSize: Long,
  val videoDuration: Int,
  // Device-Derived
  val sdkVersion: String,
  val osName: String,
  val osVersion: String,
  val deviceModel: String,
  val appName: String,
  val appVersion: String,
  val regionCode: String
) {
  fun toJson(): String {
    return JSONObject().apply {
      put("type", "upload")
      put("start_time", startTimeMillis)
      put("end_time", endTimeMillis)
      put("file_size", fileSize)
      put("video_duration", videoDuration)
      put("sdk_version", sdkVersion)
      put("os_name", osName)
      put("os_version", osVersion)
      put("device_model", deviceModel)
      put("app_name", appName)
      put("app_version", appVersion)
      put("region_code", regionCode)
    }.toString()
  }
}
