package com.mux.video.upload.internal

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.mux.video.upload.BuildConfig
import com.mux.video.upload.MuxUploadSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


internal class UploadMetrics private constructor() {

  private val logger get() = MuxUploadSdk.logger

  private suspend fun getEventInfo(startTimeMillis: Long,
                                   startTimeKey: String,
                                   endTimeMillis: Long,
                                   endTimeKey: String,
                                   inputFileDurationMs: Long,
                                   uploadInfo: UploadInfo): JSONObject {
    val iso8601Sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    var videoDuration = inputFileDurationMs
    if (inputFileDurationMs <= 0) {
      videoDuration = withContext(Dispatchers.IO) {
        try {
          MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(uploadInfo.inputFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt()
          }
        } catch (e: Exception) {
          MuxUploadSdk.logger.e("UploadMetrics", "Failed to get video duration", e)
          null
        }
      }?.toLong() ?: -1L
    }
    return JSONObject().apply {
      put(startTimeKey, iso8601Sdf.format(startTimeMillis)) // ISO8601
      put(endTimeKey, iso8601Sdf.format(endTimeMillis)) // ISO8601
      put("input_size", uploadInfo.inputFile.length())
      put("input_duration", videoDuration / 1000) // HH:mm:ss
      put("upload_url", uploadInfo.remoteUri.toString())
      put("sdk_version", BuildConfig.LIB_VERSION)
      put("platform_name", "Android")
      put("platform_version", Build.VERSION.RELEASE)
      put("device_model", Build.MODEL)
      put("device_version", Build.VERSION.SDK_INT)
      put("app_name", appName)
      put("app_version", appVersion)
      put("region_code", Locale.getDefault().country)
    }
  }

  private suspend fun sendPost(body:JSONObject) {
    val httpClient = MuxUploadSdk.httpClient()
      .newBuilder()
      // The SDK's http client is configured for uploading. We want the tighter default timeouts
      .callTimeout(0, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      // We need to do a non-compliant redirect: 301 or 302 but preserving method and body
      .addInterceptor(Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code in 301..302 || response.code in 307..308) {
          val redirectUri = response.headers("Location").firstOrNull()?.let { Uri.parse(it) }
          // If 'Location' was present and was a real URL, redirect to it
          if (redirectUri == null) {
            logger.w("UploadMetrics", "redirect with invalid or blank url. ignoring")
            response
          } else {
            response.close() // Required before starting a new request in the chain
            val redirectedReq = response.request.newBuilder()
            if (redirectUri.isRelative) {
              val requestUrl = response.request.url
              var relativePathDelimiter = "/"
              if (redirectUri.toString().startsWith("/")) {
                relativePathDelimiter = "";
              }
              redirectedReq.url(requestUrl.scheme + "//" + requestUrl.host + ":"
                      + requestUrl.port + relativePathDelimiter + redirectUri.toString())
            } else {
              redirectedReq.url(redirectUri.toString())
            }
            chain.proceed(redirectedReq.build())
          }
        } else {
          response
        }
      })
      .build()
    val request = Request.Builder()
      .url("https://mobile.muxanalytics.com/api/upload-sdk-native")
      .method("POST", body.toString().toRequestBody("application/json".toMediaType()))
      .build()
    // The HTTP Client will log if this fails or succeeds
    withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
  }

  @JvmSynthetic
  internal suspend fun reportStandardizationSuccess(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    inputReasons: JSONArray,
    maximumResolution:String,
    sessionId: String,
    uploadInfo: UploadInfo
  ) = runCatching {
    val body = JSONObject()
    body.put("type", "upload_input_standardization_succeeded")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, "standardization_start_time", endTimeMillis,
      "standardization_end_time", inputFileDurationMs, uploadInfo)
    data.put("maximum_resolution", maximumResolution)
    data.put("non_standard_input_reasons", inputReasons)
    data.put("input_standardization_requested", true)
    body.put("data", data)
    sendPost(body)
  }

  @JvmSynthetic
  internal suspend fun reportStandardizationFailed(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    errorDescription: String,
    inputReasons: JSONArray,
    maximumResolution:String,
    sessionId: String,
    uploadInfo: UploadInfo
  ) = runCatching {
    val body = JSONObject()
    body.put("type", "upload_input_standardization_failed")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, "standardization_start_time",
      endTimeMillis, "standardization_end_time", inputFileDurationMs, uploadInfo)
    data.put("error_description", errorDescription)
    data.put("maximum_resolution", maximumResolution)
    data.put("non_standard_input_reasons", inputReasons)
    data.put("upload_canceled", false)
    data.put("input_standardization_requested", true)
    body.put("data", data)
    sendPost(body)
  }

  @JvmSynthetic
  internal suspend fun reportUploadSucceeded(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    sessionId: String,
    uploadInfo: UploadInfo
  ) = runCatching {
    val body = JSONObject()
    body.put("type", "upload_succeeded")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, "upload_start_time", endTimeMillis,
      "upload_end_time", inputFileDurationMs, uploadInfo)
    data.put("input_standardization_requested", uploadInfo.standardizationRequested
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
    body.put("data", data)
    sendPost(body)
  }

  @JvmSynthetic
  internal suspend fun reportUploadFailed(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    errorDescription:String,
    sessionId: String,
    uploadInfo: UploadInfo
  ) = runCatching {
    val body = JSONObject()
    body.put("type", "uploadfailed")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, "upload_start_time", endTimeMillis,
      "upload_end_time", inputFileDurationMs, uploadInfo)
    data.put("input_standardization_requested", uploadInfo.standardizationRequested
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
    data.put("error_description", errorDescription)
    body.put("data", data)
    sendPost(body)
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
