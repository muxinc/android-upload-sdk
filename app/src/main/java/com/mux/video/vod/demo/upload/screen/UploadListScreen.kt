package com.mux.video.vod.demo.upload.screen

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.UploadStatus
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.CreateUploadActivity
import com.mux.video.vod.demo.upload.CreateUploadCta
import com.mux.video.vod.demo.upload.MuxAppBar
import com.mux.video.vod.demo.upload.THUMBNAIL_SIZE
import com.mux.video.vod.demo.upload.model.extractThumbnail
import com.mux.video.vod.demo.upload.ui.theme.Gray70
import com.mux.video.vod.demo.upload.ui.theme.Gray90
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme
import com.mux.video.vod.demo.upload.ui.theme.TranslucentScrim
import com.mux.video.vod.demo.upload.ui.theme.TranslucentWhite
import com.mux.video.vod.demo.upload.viewmodel.UploadListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

@Composable
fun UploadListScreen() {
  val activity = LocalContext.current as Activity
  val closeScreen = { activity.finish() }
  ScreenContent(closeScreen)
}

@Composable
private fun ScreenContent(
  closeThisScreen: () -> Unit = {},
) {
  val listItemsState = screenViewModel().uploads.observeAsState()

  val activity = LocalContext.current as? Activity
  val uploadClick: () -> Unit =
    { activity?.startActivity(Intent(activity, CreateUploadActivity::class.java)) }

  return Scaffold(
    topBar = { ScreenAppBar(closeThisScreen) },
    floatingActionButton = {
      if (listItemsState.value?.isEmpty() == false) {
        CreateUploadFab(uploadClick)
      }
    }
  ) { contentPadding ->
    BodyContent(
      Modifier.padding(contentPadding),
      listItemsState.value,
      uploadClick
    )
  }
}

@Composable
private fun CreateUploadFab(uploadClick: () -> Unit) {
  FloatingActionButton(
    onClick = { uploadClick() },
    backgroundColor = MaterialTheme.colors.primary,
  ) {
    Icon(painterResource(id = R.drawable.ic_add), contentDescription = "Start new upload")
  }
}

@Composable
private fun BodyContent(
  modifier: Modifier = Modifier,
  items: List<MuxUpload>?,
  uploadClick: () -> Unit
) {
  val viewModel = screenViewModel()
  SideEffect { viewModel.refreshList() }

  Box(
    modifier = modifier.padding(
      PaddingValues(
        start = 20.dp,
        end = 20.dp,
      )
    )
  ) {
    if (items == null || items.isEmpty()) {
      CreateUploadCta(modifier = Modifier.padding(vertical = 64.dp)) { uploadClick() }
    } else {
      UploadList(items)
    }
  }
}

@Composable
private fun UploadList(items: List<MuxUpload>) {
  val listState = rememberLazyListState()
  ReportDrawnWhen { listState.layoutInfo.totalItemsCount > 0 }
  LazyColumn(
    state = listState,
    contentPadding = PaddingValues(vertical = 64.dp),
    verticalArrangement = Arrangement.spacedBy(32.dp)
  ) {
    // show items in reverse order by their start time (newest-first)
    items(
      items.reversed(),
      key = { it.videoFile.hashCode() }
    ) { ListItemContent(upload = it) }
  }
}

@Composable
private fun ListItemContent(upload: MuxUpload) {
  Box(
    modifier = Modifier
      .wrapContentSize(Alignment.Center)
      .fillMaxWidth()
      .height(THUMBNAIL_SIZE)
      .clip(RoundedCornerShape(12.dp))
  ) {
    val imageBitmapState = remember { mutableStateOf<Bitmap?>(null) }
    val bitmap = imageBitmapState.value
    // If the bitmap in the state ever becomes null reload it
    if (bitmap == null) {
      LaunchedEffect(imageBitmapState.value) {
        launch(Dispatchers.IO) {
          imageBitmapState.value = extractThumbnail(upload.videoFile)
        }
      }
    }

    val uploadState = remember { mutableStateOf(upload.uploadStatus) }
    upload.setStatusListener { uploadState.value = it }

    ListThumbnail(bitmap = bitmap)
    if (uploadState.value.isSuccessful()) {
      DoneOverlay()
    } else if (uploadState.value.getError() != null) {
      ErrorOverlay(modifier = Modifier.fillMaxSize())
    } else if (upload.isRunning) {
      ProgressOverlay(
        upload.currentProgress,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
      )
    }
  } // outer Box
} // ListItemContent

@Composable
fun DoneOverlay(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.fillMaxSize()
  ) {
    Icon(
      Icons.Filled.Check,
      contentDescription = "Upload complete",
      tint = MaterialTheme.colors.onSecondary,
      modifier = modifier
        .background(color = MaterialTheme.colors.secondary, shape = CircleShape)
        .align(Alignment.Center)
        .size(40.dp)
    )
  }
}

@Composable
private fun ProgressOverlay(uploadState: MuxUpload.Progress, modifier: Modifier = Modifier) {
  val uploadTimeElapsed = uploadState.updatedTime - uploadState.startTime
  val dataRateEst = uploadState.bytesUploaded / uploadTimeElapsed.toDouble()
  Box(
    modifier = modifier.background(TranslucentScrim)
  ) {
    Column(
      verticalArrangement = Arrangement.Bottom,
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(8.dp)
    ) {
      Text(
        "Uploading",
        style = TextStyle(
          color = Color.White,
          fontSize = 14.sp
        ),
      )
      Spacer(modifier = Modifier.size(8.dp))
      val uploadProgress = uploadState.bytesUploaded / uploadState.totalBytes.toDouble()
      LinearProgressIndicator(
        progress = uploadProgress.toFloat(),
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = TranslucentWhite
      )
      Spacer(modifier = Modifier.size(2.dp))
      val df = DecimalFormat("#.00")
      val formattedRate = df.format(dataRateEst)
      val formattedTime = df.format(uploadTimeElapsed / 1000f)
      val formattedTotalBytes = df.format(uploadState.bytesUploaded / (1024f * 1024f))
      val stateLine = "$formattedTotalBytes Mb in ${formattedTime}s (${formattedRate}Kbps)"
      Text(
        stateLine,
        style = TextStyle(color = Color.White, fontSize = 14.sp),
        modifier = Modifier
      )
    }
  }
}

@Composable
private fun ErrorOverlay(modifier: Modifier = Modifier) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = modifier
      .background(TranslucentScrim)
  ) {
    Icon(
      Icons.Filled.Error,
      contentDescription = "error",
      tint = Color.White,
      modifier = Modifier
        .size(36.dp)
    )
    Spacer(modifier = Modifier.size(12.dp))
    Text(
      "Upload failed!",
      style = TextStyle(
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
      ),
    )
    Text(
      "Try again with another file",
      style = TextStyle(color = Color.White, fontSize = 12.sp),
    )
  }
}

@Composable
private fun ListThumbnail(modifier: Modifier = Modifier, bitmap: Bitmap?) {
  if (bitmap != null) {
    Image(
      bitmap = bitmap.asImageBitmap(),
      contentDescription = "Video thumbnail preview",
      contentScale = ContentScale.Crop,
      modifier = modifier
        .height(THUMBNAIL_SIZE)
        .clip(shape = RoundedCornerShape(12.dp))
    )
  } else {
    Box(
      modifier = modifier
        .fillMaxSize()
        .background(color = Gray90, shape = RoundedCornerShape(12.dp))
        .border(
          width = 1.dp,
          color = Gray70,
          shape = RoundedCornerShape(12.dp),
        )
        .clip(RoundedCornerShape(12.dp))
    )
  }
}

@Composable
private fun ScreenAppBar(closeThisScreen: () -> Unit) {
  MuxAppBar()
}

@Composable
private fun screenViewModel(): UploadListViewModel = viewModel()

@Preview(showBackground = true)
@Composable
fun ListItemError() {
  MuxUploadSDKForAndroidTheme {
    ErrorOverlay()
  }
}

@Preview(showBackground = true)
@Composable
fun NoUploads() {
  MuxUploadSDKForAndroidTheme {
    CreateUploadCta { }
  }
}

@Preview(showBackground = true, locale = "en")
@Composable
fun ListItemProgress() {
  MuxUploadSDKForAndroidTheme {
    Box(modifier = Modifier.height(THUMBNAIL_SIZE)) {
      ProgressOverlay(
        uploadState = MuxUpload.Progress(
          startTime = System.currentTimeMillis() - TimeUnit.MILLISECONDS.convert(
            10,
            TimeUnit.MINUTES
          ),
          updatedTime = System.currentTimeMillis(),
          bytesUploaded = 100 * 1024 * 1024,
          totalBytes = 175 * 1024 * 1024,
        ),
        modifier = Modifier.align(Alignment.BottomStart)
      )
    }
  }
}
