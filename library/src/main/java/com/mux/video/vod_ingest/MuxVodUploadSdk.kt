package com.mux.video.vod_ingest

import android.util.Log

/**
 * Uploads videos to Mux Video.
 *
 * TODO: This would be a good place to put usage
 */
object MuxVodUploadSdk {
  const val VERSION = BuildConfig.LIB_VERSION

  /**
   * Logs messages from the SDK. By default, no logging is performed
   * To get logs from this SDK, use [useLogger], eg:
   * ```
   * changeLogger(MuxVodUploadSdk.logcatLogger())
   * ```
   * Inf
   */
  val logger by this::_logger
  private var _logger: Logger

  init {
    if (BuildConfig.DEBUG) {
      _logger = logcatLogger()
    } else {
      _logger = NoLogger()
    }
  }

  fun useLogger(logger: Logger) {
    _logger = logger
  }
  fun logcatLogger(): Logger = LogcatLogger()

  fun systemOutLogger(): Logger = SystemOutLogger()

  fun noLogger(): Logger = NoLogger()

  /**
   * Logger for this instance
   */
  interface Logger {
    fun e(tag: String = "", msg: String, e: Exception? = null)
    fun w(tag: String = "", msg: String, e: Exception? = null)
    fun d(tag: String = "", msg: String, e: Exception? = null)
    fun i(tag: String = "", msg: String, e: Exception? = null)
    fun v(tag: String = "", msg: String, e: Exception? = null)
  }
}

private class LogcatLogger : MuxVodUploadSdk.Logger {
  override fun e(tag: String, msg: String, e: Exception?) {
    Log.e(tag, msg, e)
  }

  override fun w(tag: String, msg: String, e: Exception?) {
    Log.w(tag, msg, e)
  }

  override fun d(tag: String, msg: String, e: Exception?) {
    Log.d(tag, msg, e)
  }

  override fun i(tag: String, msg: String, e: Exception?) {
    Log.i(tag, msg, e)
  }

  override fun v(tag: String, msg: String, e: Exception?) {
    Log.v(tag, msg, e)
  }
}

// For unit tests
private class SystemOutLogger: MuxVodUploadSdk.Logger {
  override fun e(tag: String, msg: String, e: Exception?) {
    print("E", tag, msg, e)
  }

  override fun w(tag: String, msg: String, e: Exception?) {
    print("W", tag, msg, e)
  }

  override fun d(tag: String, msg: String, e: Exception?) {
    print("D", tag, msg, e)
  }

  override fun i(tag: String, msg: String, e: Exception?) {
    print("I", tag, msg, e)
  }

  override fun v(tag: String, msg: String, e: Exception?) {
    print("V", tag, msg, e)
  }

  private fun print(pri: String, tag: String, msg: String, e:Exception?) {
    println("$pri // $tag: $msg\n${e ?: ""}")
  }
}

// For prod
private class NoLogger: MuxVodUploadSdk.Logger {
  override fun e(tag: String, msg: String, e: Exception?) { }
  override fun w(tag: String, msg: String, e: Exception?) { }
  override fun d(tag: String, msg: String, e: Exception?) { }
  override fun i(tag: String, msg: String, e: Exception?) { }
  override fun v(tag: String, msg: String, e: Exception?) { }
}