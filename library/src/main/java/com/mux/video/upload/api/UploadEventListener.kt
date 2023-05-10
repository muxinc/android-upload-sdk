package com.mux.video.upload.api

/**
 * Listens for events from the Mux Upload SDK
 */
fun interface UploadEventListener<EventType> {
  fun onEvent(event: EventType)
}