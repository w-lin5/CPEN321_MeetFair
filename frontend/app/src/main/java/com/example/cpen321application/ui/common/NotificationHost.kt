package com.example.cpen321application.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.cpen321application.ui.theme.Dimens
import kotlinx.coroutines.launch

val LocalNotify = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Owned by the signed-in shell so notifications survive returning from a deleted page. */
@Composable
fun NotificationHost(content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = remember(host, scope) {
        { message -> scope.launch { host.showSnackbar(message, duration = SnackbarDuration.Short) }; Unit }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalNotify provides notify) { content() }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Dimens.ScreenPadding))
    }
}
