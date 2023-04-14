package com.mux.video.vod.demo.upload

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme
import com.mux.video.vod.demo.upload.viewmodel.CreateUploadViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

class CreateUploadActivity : ComponentActivity() {

  private val viewModel by viewModels<CreateUploadViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MuxUploadSDKForAndroidTheme {
        if (!hasPermissions(this)) {
          RequestPermissionsEffect(this)
        } else {
          GetContentEffect(viewModel = viewModel)
        }

        val state = viewModel.videoState.observeAsState(
          CreateUploadViewModel.State(CreateUploadViewModel.PrepareState.NONE, null)
        )
        ScreenContent(closeThisScreen = { finish() }, screenState = state.value)
      }
    }
  }
}

@Composable
fun RequestPermissionsEffect(context: Context) {
  val permissionsState = remember { mutableStateOf(hasPermissions(context)) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantedPermissions: Map<String, Boolean>? ->
      grantedPermissions!! // The system shouldn't ever give us null here
      permissionsState.value =
        grantedPermissions.values.reduce { hasAll, hasThisOne -> hasThisOne && hasAll }
    }
  if (!permissionsState.value) {
    LaunchedEffect(key1 = Object()) {
      MainScope().launch {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          launcher.launch(
            arrayOf(
              android.Manifest.permission.READ_EXTERNAL_STORAGE,
              android.Manifest.permission.READ_MEDIA_VIDEO
            )
          )
        } else {
          launcher.launch(
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
          )
        }
      } // MainScope().launch {
    } // LaunchedEffect
  } // if (!permissionState)
}

@Composable
fun GetContentEffect(viewModel: CreateUploadViewModel) {
  val contentUri = remember { mutableStateOf<Uri?>(null) }
  val getContent =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      contentUri.value = uri
      uri?.let { viewModel.prepareForUpload(it) }
    }
  if (contentUri.value == null) {
    LaunchedEffect(key1 = Object()) {
      MainScope().launch { getContent.launch(arrayOf("video/*")) }
    }
  }
}

@Composable
fun ScreenContent(
  closeThisScreen: () -> Unit = {},
  screenState: CreateUploadViewModel.State
) {
  return Scaffold(
    topBar = {
      AppBar(closeThisScreen, screenState.chosenFile)
    },
  ) { contentPadding ->
    BodyContent(
      screenState,
      Modifier
        .padding(contentPadding)
        .padding(16.dp)
    )
  }
}

@Composable
fun BodyContent(state: CreateUploadViewModel.State, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .wrapContentSize(Alignment.TopStart)
  ) {
    Row {
      Box(
        modifier = Modifier
          .clip(CircleShape)
          .background(Color.Gray)
          .size(96.dp)
      )
      Spacer(modifier = Modifier.size(16.dp))
      val stateTxtModifier = Modifier
        .fillMaxWidth()
        .align(CenterVertically)
      Box(
        modifier = stateTxtModifier
      ) {
        if (state.chosenFile != null) {
          Text(
            text = "Will upload video file: ${state.chosenFile.name}",
            modifier = stateTxtModifier,
            fontSize = 16.sp
          )
        } else if (state.prepareState == CreateUploadViewModel.PrepareState.ERROR) {
          Text(
            text = "Error preparing this video for upload",
            modifier = stateTxtModifier,
            fontSize = 16.sp
          )
        } else {
          val viewModel: CreateUploadViewModel = viewModel()
          val requestContent = remember { mutableStateOf(false) }
          if (requestContent.value) {
            requestContent.value = false
            GetContentEffect(viewModel = viewModel)
          }
          TextButton(onClick = {
            requestContent.value = viewModel.videoState.value?.chosenFile == null
          }) {
            Text(
              text = buildAnnotatedString {
                withStyle(style = SpanStyle(color = Color.Blue, fontWeight = FontWeight.SemiBold)) {
                  append("Click")
                }
                withStyle(
                  style = SpanStyle(
                    color = MaterialTheme.colors.onBackground,
                    fontWeight = FontWeight.Normal
                  )
                ) {
                  append(" to choose a video for upload")
                }
              },
              modifier = stateTxtModifier,
              fontSize = 16.sp,
            )
          }
        }
      }
    } // Box
    Spacer(modifier = Modifier.size(16.dp))
    // Thumbnail is next, or placeholders for error and not-chosen states
    if (state.thumbnail != null) {
      val imageBitmap = state.thumbnail.asImageBitmap()
      Image(
        bitmap = imageBitmap,
        contentDescription = "Preview of the video thumbnail",
        contentScale = ContentScale.Crop,
        modifier = Modifier.height(256.dp),
      )
    } else {
      Box(
        modifier = Modifier
          .wrapContentSize(Alignment.Center)
          .fillMaxWidth()
          .height(256.dp)
          .border(
            width = 1.dp,
            color = Color.LightGray,
            shape = RoundedCornerShape(12.dp),
          )
      ) {
        val viewModel: CreateUploadViewModel = viewModel()
        val videoState = viewModel.videoState.value

        when (state.prepareState) {
          CreateUploadViewModel.PrepareState.ERROR -> {
            Icon(
              Icons.Outlined.Error,
              contentDescription = "",
              modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp),
            )
          }
          CreateUploadViewModel.PrepareState.PREPARING -> {
            CircularProgressIndicator(
              modifier = Modifier.align(Alignment.Center).size(48.dp)
            )
          }
          else -> {
            Icon(
              Icons.Outlined.UploadFile,
              contentDescription = "",
              modifier = Modifier
                .alpha(0.6F)
                .align(Alignment.Center)
                .size(48.dp),
            )
          } // else ->
        } // when (state.prepareState)
      } // Box Content
    } // Box
  }
}

@Composable
fun AppBar(closeThisScreen: () -> Unit, videoFile: File?) {
  val viewModel: CreateUploadViewModel = viewModel()
  val enableAction = videoFile != null
  TopAppBar(
    title = { Text(text = stringResource(R.string.title_activity_create_upload)) },
    navigationIcon = {
      IconButton(
        onClick = {
          closeThisScreen()
        },
        enabled = videoFile != null
      ) {
        Icon(
          Icons.Filled.Close,
          contentDescription = stringResource(id = android.R.string.cancel),
        )
      }
    },
    actions = {
      TextButton(
        onClick = {
          viewModel.beginUpload()
          closeThisScreen()
        },
        enabled = enableAction
      ) {
        Text(
          text = stringResource(id = R.string.action_create_upload),
          style = TextStyle(color = MaterialTheme.colors.onPrimary),
          modifier = Modifier.alpha(
            if (enableAction) {
              1.0F
            } else {
              0.6F
            }
          )
        )
      }
    },
  )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
  MuxUploadSDKForAndroidTheme {
    ScreenContent(
      screenState = CreateUploadViewModel.State(
        prepareState = CreateUploadViewModel.PrepareState.NONE,
        thumbnail = null
      ),
    )
  }
}

private fun hasPermissions(context: Context): Boolean {
  val hasVideo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(
      context,
      android.Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED
  } else {
    true
  }
  val hasExternalStorage = ContextCompat.checkSelfPermission(
    context,
    android.Manifest.permission.READ_EXTERNAL_STORAGE
  ) == PackageManager.PERMISSION_GRANTED
  return hasVideo && hasExternalStorage
}
