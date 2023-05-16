package com.mux.video.upload.api

/**
 * Listens for events from the Mux Upload SDK
 */
fun interface UploadEventListener<EventType> {
  /**
   * Called when an event is generated
   */
  fun onEvent(event: EventType)
}