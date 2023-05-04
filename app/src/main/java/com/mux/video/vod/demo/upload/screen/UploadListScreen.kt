package com.mux.video.vod.demo.upload.screen

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mux.video.upload.api.MuxUpload
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
import com.mux.video.vod.demo.upload.viewmodel.UploadListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DecimalFormat

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
    items(items.reversed()) { ListItemContent(upload = it) }
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
    LaunchedEffect(imageBitmapState.value) {
      // If the bitmap in the state ever becomes null reload it
      if (bitmap == null) {
        launch(Dispatchers.IO) {
          imageBitmapState.value = extractThumbnail(upload.videoFile)
        }
      }
    }

    ListThumbnail(bitmap = bitmap)

    if (upload.isSuccessful) {
      // No overlay for now
    } else if (upload.error != null) {
      ErrorOverlay(modifier = Modifier.fillMaxSize())
    } else if (upload.isRunning) {
      ProgressOverlay(upload, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
  } // outer Box
} // ListItemContent

@Composable
private fun ProgressOverlay(upload: MuxUpload, modifier: Modifier = Modifier) {
  val uploadState = upload.currentState
  val uploadTimeElapsed = uploadState.updatedTime - uploadState.startTime
  val dataRateEst = uploadState.bytesUploaded / uploadTimeElapsed.toDouble()
  Box(
    modifier = modifier.background(TranslucentScrim)
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.Companion
        .align(Alignment.Center)
        .fillMaxWidth()
    ) {
      Text(
        "Uploading",
        style = TextStyle(
          color = Color.White,
          fontWeight = FontWeight.SemiBold,
          fontSize = 14.sp
        ),
      )
      Spacer(modifier = Modifier.size(12.dp))
      val uploadProgress = uploadState.bytesUploaded / uploadState.totalBytes.toDouble()
      LinearProgressIndicator(
        progress = uploadProgress.toFloat(),
        color = MaterialTheme.colors.secondary,
        modifier = Modifier.fillMaxWidth(0.55F)
      )
      Spacer(modifier = Modifier.size(6.dp))
      Text(
        text = "${uploadState.bytesUploaded} / ${uploadState.totalBytes}",
        fontSize = 10.sp,
        color = Color.LightGray
      )
    }
    val stateLine = buildAnnotatedString {
      val df = DecimalFormat("#.00")
      val formattedRate = df.format(dataRateEst)
      val formattedTime = df.format(uploadTimeElapsed / 1000f)
      withStyle(
        style = SpanStyle(
          fontWeight = FontWeight.Medium,
        )
      ) {
        append("$formattedRate Kb/s")
      }
      append(" in ${formattedTime}s")
    }
    Text(
      stateLine,
      style = TextStyle(color = Color.White, fontSize = 16.sp),
      modifier = Modifier
        .padding(12.dp)
        .align(Alignment.BottomStart)
    )
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
fun ListScreenPreview() {
  MuxUploadSDKForAndroidTheme {
    ScreenContent {}
  }
}
