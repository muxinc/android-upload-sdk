package com.mux.video.vod.demo.mediastore

import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.databinding.ActivityVideoListBinding
import com.mux.video.vod.demo.mediastore.model.UploadingVideo

class MediaStoreVideosActivity : AppCompatActivity() {

  companion object {
    // For now, you have to paste this from the direct-upload response
    const val PUT_URL =
      "https://storage.googleapis.com/video-storage-gcp-us-east1-vop1-uploads/6FkEfkd3weRHdZmkosSA9I?Expires=1679699240&GoogleAccessId=uploads-gcp-us-east1-vop1%40mux-video-production.iam.gserviceaccount.com&Signature=VDr07%2Bl4lzSPVeq4iRs8f2evmpZ6pRKNIbv8%2ByrrfpMTsc7lkAaYwKcZTbNqxnDfZC17W5Stejf74420NXXwG3ugPIvclk1Vjp5X6wbO5KODM3SpQ9upEyqZAgkfZ0tGwHzBFegmjhQTq3MBCf2HRsC47pAh9DoW2MD3Wn2m6vXFFpY80XL%2BXg2mqubgzrsrIl%2BbeTh%2FKYANBDRLfuNQeJkk9eC1qHNY6J1T9JunOySLPy%2F3SE3Hq4OVJ%2BZP1RuVJ%2FfuDV0GvlSGf4wQ9HkosepkkvJSx5XKShzB9DPRjpMEJ%2F7zND21%2Bh4j4diWxh2oaE2plaVU7cpEKKcY8yzP1Q%3D%3D&upload_id=ADPycdvxUba_gDB9eLqLNiqV14_hJ1aedTnVRElyrpIfTy1V8Rhf2txJ1C01_IHakzqS68AN5HARp9Oz31CNzhg14slZqA"
  }

  private lateinit var binding: ActivityVideoListBinding
  private lateinit var listAdapter: MediaStoreVideosAdapter
  private val viewModel by viewModels<MediaStoreVideosViewModel>()
  private val requestPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { grantedPermissions ->
      if (!grantedPermissions.containsKey(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        || grantedPermissions.containsKey(android.Manifest.permission.READ_MEDIA_VIDEO)
      ) {
        maybeRequestPermissionsApi33()
      }
    }
  private val openDocument =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { docUri ->
      Log.d(javaClass.simpleName, "Got doc with URI $docUri")
      viewModel.beginUpload(docUri!!)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityVideoListBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.videoListList.includeRecyclerView.layoutManager = LinearLayoutManager(this)
    viewModel.uploads.observe(this) { handleListUpdate(it) }

    setSupportActionBar(findViewById(R.id.toolbar))
    binding.toolbarLayout.title = title
    binding.fab.setOnClickListener { view ->
      openDocument.launch(arrayOf("video/*"))
    }

    maybeRequestPermissions()
  }

  private fun handleListUpdate(list: List<MuxUpload>) {
    // TODO: Use AsyncListDiffer to make this look nice
    listAdapter = MediaStoreVideosAdapter(list)
    binding.videoListList.includeRecyclerView.adapter = listAdapter
  }

  private fun maybeRequestPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      maybeRequestPermissionsApi33()
    } else {
      maybeRequestPermissionsOld()
    }
  }

  private fun maybeRequestPermissionsOld() {
    val hasReadStorage =
      ActivityCompat.checkSelfPermission(
        this,
        android.Manifest.permission.READ_EXTERNAL_STORAGE
      ) == PackageManager.PERMISSION_GRANTED

    if (!hasReadStorage) {
      requestPermissions.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    }
  }

  @TargetApi(Build.VERSION_CODES.TIRAMISU)
  private fun maybeRequestPermissionsApi33() {
    val hasReadStorage =
      ActivityCompat.checkSelfPermission(
        this,
        android.Manifest.permission.READ_EXTERNAL_STORAGE
      ) == PackageManager.PERMISSION_GRANTED
    val hasReadVideo = ActivityCompat.checkSelfPermission(
      this,
      android.Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasReadVideo || !hasReadStorage) {
      requestPermissions.launch(
        arrayOf(
          android.Manifest.permission.READ_EXTERNAL_STORAGE,
          android.Manifest.permission.READ_MEDIA_VIDEO
        )
      )
    }
  }

}