package com.example.cpen321application.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary

/** 
 * MeetFair brand header: Pin Logo + MEETFAIR title + Profile avatar button.
 */
@Composable
fun MeetFairHeader(
    modifier: Modifier = Modifier,
    onProfile: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = TealContainer,
                shape = CircleShape,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = RosePrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Text(
                text = "MEETFAIR",
                style = MaterialTheme.typography.titleLarge,
                color = TealPrimary,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
        }

        if (onProfile != null) {
            Surface(
                onClick = onProfile,
                color = TealContainer,
                shape = CircleShape,
                modifier = Modifier
                    .size(40.dp)
                    .testTag(TestTags.PROFILE_BUTTON),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Profile",
                        tint = TealPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** 
 * Standard screen layout: title at the top, optional back button, content below.
 * Keeps content clear of the status bar, nav bar and keyboard. 
 */
@Composable
fun ScreenColumn(
    title: String,
    onBack: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 4.dp)
                    .testTag(TestTags.BACK_BUTTON)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TealPrimary,
                )
            }
        } else {
            MeetFairHeader(onProfile = onProfile)
        }
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = TealPrimary,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        content()
    }
}

@Composable
fun LoadingView() {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.testTag(TestTags.LOADING))
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    buttonTestTag: String = TestTags.RETRY_BUTTON,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, TealContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TestTags.ERROR_TEXT),
            )
            Button(
                onClick = onRetry,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TealPrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(buttonTestTag),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyView(message: String) {
    Text(message, modifier = Modifier.testTag(TestTags.EMPTY))
}

/** Texts and test tags of a [SingleFieldFormScreen]. */
data class FormSpec(
    val title: String,
    val label: String,
    val submitLabel: String,
    val inputTag: String,
    val submitTag: String,
)

/** One text field plus a submit button; calls [onSuccess] once with the result when the submission succeeds. */
@Composable
fun <T> SingleFieldFormScreen(
    spec: FormSpec,
    state: FormState<T>,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onSuccess: (T) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.result) { state.result?.let(onSuccess) }
    
    ScreenColumn(spec.title, onBack) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(spec.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(spec.inputTag),
        )
    
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }
    
        Button(
            onClick = { onSubmit(input) },
            enabled = !state.submitting && input.isNotBlank(),
            modifier = Modifier.testTag(spec.submitTag),
        ) { Text(spec.submitLabel) }
    }
}

/** Read-only screens refresh with a downward swipe; forms do not use this container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> UiStateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    emptyMessage: String,
    content: @Composable (T) -> Unit,
) {
    // Keep the same scrollable subtree while loading, preserving visible content and scroll position.
    var lastDisplayedState by remember { mutableStateOf<UiState<T>>(UiState.Loading) }
    SideEffect { if (state !is UiState.Loading) lastDisplayedState = state }
    val displayedState = if (state is UiState.Loading) lastDisplayedState else state
    PullToRefreshBox(
        isRefreshing = state is UiState.Loading && lastDisplayedState !is UiState.Loading,
        onRefresh = { if (state !is UiState.Loading) onRetry() },
        modifier = Modifier.fillMaxSize().testTag("pull_to_refresh"),
    ) {
        when (displayedState) {
            UiState.Loading -> LoadingView()
            UiState.Empty -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EmptyView(emptyMessage)
            }
            is UiState.Error -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ErrorView(displayedState.message, onRetry)
            }
            is UiState.Success -> content(displayedState.data)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MeetFairHeaderPreview() {
    CPEN321ApplicationTheme {
        MeetFairHeader(onProfile = {})
    }
}
