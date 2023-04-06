package com.mux.video.upload.internal

@JvmSynthetic
internal fun log(tag: String = "\t", message: String, ex: Exception? = null) {
  println("$tag :: $message")
  ex?.let {
    print(it)
    println()
  }
}
