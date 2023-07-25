package com.mux.video.upload.api

/**
 * Helper class for parsing [Result] objects from [MuxUpload] in Java.
 *
 * Kotlin callers can use the [Result] API as normal
 */
class UploadResult {

  companion object {
    /**
     * Returns true of the upload was successful,
     * Returns false if the upload wasn't successful, or if the passed object wasn't a [Result]
     */
    @Suppress("unused")
    @JvmStatic
    fun isSuccessful(result: Result<MuxUpload.Progress>): Boolean {
      @Suppress("USELESS_IS_CHECK") // java interprets inline classes like Result as Object
      return if (result is Result) {
        result.isSuccess
      } else {
        false
      }

    }

    /**
     * Returns the final Progress update from [MuxUpload]'s [Result] if if was successful
     * Returns `null` if the upload was not successful, or if the passed object wasn't a result
     */
    @Suppress("unused")
    @JvmStatic
    fun getFinalProgress(result: Result<MuxUpload.Progress>): MuxUpload.Progress? {
      @Suppress("USELESS_IS_CHECK") // java interprets inline classes like Result as Object
      return if (result is Result) {
       result.getOrNull()
      } else {
        null
      }
    }

    /**
     * If the Result was not successful, returns the Exception that caused the failure
     */
    @Suppress("unused")
    @JvmStatic
    fun getError(result: Result<MuxUpload.Progress>): Throwable? {
      @Suppress("USELESS_IS_CHECK") // java interprets inline classes like Result as Object
      return if (result is Result) {
        result.exceptionOrNull()
      } else {
        null
      }
    }
  }
}
