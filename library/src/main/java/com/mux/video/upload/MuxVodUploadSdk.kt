package com.mux.video.upload

import android.util.Log

/**
 * Uploads videos to Mux Video.
 *
 * TODO: This would be a good place to put usage
 */
object MuxVodUploadSdk {
  /**
   * The current version of this SDK. Release builds of this SDK strictly follow the rules of
   * semantic versioning (https://semver.org)
   */
  const val VERSION = BuildConfig.LIB_VERSION

  /**
   * Logs messages from the SDK. By default, no logging is performed
   * To get logs from this SDK, use [useLogger], eg:
   * ```
   * changeLogger(MuxVodUploadSdk.logcatLogger()) // Log to logcat
   * ```
   * If you're using another logging library, you can always implement your own [Logger]
   */
  val logger by this::_logger
  private var _logger: Logger

  init {
    // BuildConfig is *our* build, not the clients
    _logger = if (BuildConfig.DEBUG) {
      LogcatLogger()
    } else {
      NoLogger()
    }
  }

  /**
   * Use the specified logger for logging events in this SDK. This SDK produces no logs by default.
   * If you need to debug your integration, you can add a [logcatLogger] or [systemOutLogger] or
   * implement your own [Logger] for logging libs like Timber or Firebase Crashlytics
   */
  @Synchronized
  fun useLogger(logger: Logger) {
    _logger = logger
  }

  /**
   * Creates a new [Logger] that logs to android logcat
   */
  fun logcatLogger(): Logger = LogcatLogger()

  /**
   * Creates a new [Logger] that logs to System.out. Useful for unit test environments, where [Log]
   * doesn't work
   */
  fun systemOutLogger(): Logger = SystemOutLogger()

  /**
   * Creates a new [Logger] that produces *no* output. It does nothing and discards all input. This
   * is the default logger.
   */
  fun noLogger(): Logger = NoLogger()

  /**
   * Logs events from this SDK. This interface roughly matches the interface of [Log].
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