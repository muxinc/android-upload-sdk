package com.mux.video.vod.demo.upload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class CreateUploadActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MuxUploadSDKForAndroidTheme {
        // A surface container using the 'background' color from the theme
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
          ScreenContent(closeThisScreen = { finish() })
        }
      }
    }
  }
}

@Composable
fun ScreenContent(closeThisScreen: () -> Unit = {}) {
  // TODO: So when the viewmodel says the file is available (listen from here I guess) then we can
  //  call Ap
  return Scaffold(
    topBar = { AppBar(closeThisScreen, true /*TODO*/) },
  ) { contentPadding ->
    Text("asflkjh", modifier = Modifier.padding(contentPadding))
  }
}

@Composable
fun AppBar(closeThisScreen: () -> Unit, fileAvailable: Boolean) {
  TopAppBar(
    title = { Text(text = stringResource(R.string.title_activity_create_upload)) },
    navigationIcon = {
      IconButton(onClick = {
        closeThisScreen() // Since we're not using Compose for everything, improvise the nav
      },
      enabled = fileAvailable) {
        Icon(
          Icons.Filled.Close,
          contentDescription = stringResource(id = android.R.string.cancel)
        )
      }
    },
    actions = {
      TextButton(onClick = {
        handleCreateUpload()
        closeThisScreen()
      }) {
        Text(
          text = stringResource(id = R.string.action_create_upload),
          style = TextStyle(
            color = MaterialTheme.colors.onPrimary
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
    ScreenContent()
  }
}

private fun handleCreateUpload() {
  // TODO: Make the ViewModel start the MuxUpload and then close the screen
}
