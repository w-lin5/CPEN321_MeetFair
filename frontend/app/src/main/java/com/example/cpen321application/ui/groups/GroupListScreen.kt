package com.example.cpen321application.ui.groups

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.cpen321application.AppContainer
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import kotlinx.coroutines.launch

// TODO: IMPORTANNT -- change later, just for visual test to check that everything working as intended
@Composable
fun GroupListScreen(container: AppContainer,onOpenVoting: (String) -> Unit,) {
    val name by container.sessionStore.userName.collectAsState()
    val scope = rememberCoroutineScope()
    ScreenColumn("MeetFair") {
        Text("Signed in as ${name ?: "…"}")
        OutlinedButton(
            onClick = { scope.launch { container.authRepository.signOut() } },
            modifier = Modifier.testTag(TestTags.SIGN_OUT_BUTTON),
        ) { Text("Sign out") }
    }
}
