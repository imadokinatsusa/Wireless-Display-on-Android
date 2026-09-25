package com.mirror.cast.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** 三个界面：首页、发送端、接收端。M1 不做导航库，一个状态就够。 */
private enum class MirrorScreen { Home, Sender, Receiver }

@Composable
fun MirrorApp(lastCrash: String?) {
    var screen by remember { mutableStateOf(MirrorScreen.Home) }

    MirrorTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                MirrorScreen.Home -> HomeScreen(
                    onSender = { screen = MirrorScreen.Sender },
                    onReceiver = { screen = MirrorScreen.Receiver },
                    lastCrash = lastCrash,
                )

                MirrorScreen.Sender -> SenderScreen(onBack = { screen = MirrorScreen.Home })

                MirrorScreen.Receiver -> ReceiverScreen(onBack = { screen = MirrorScreen.Home })
            }
        }
    }
}
