package com.example.cpen321application.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.R
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.AuthRepository
import com.example.cpen321application.model.User
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.TealPrimary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Holds the sign-in attempt state: idle, submitting, failed, or succeeded. */
class SignInViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(FormState<User>())
    val state: StateFlow<FormState<User>> = _state.asStateFlow()

    /** The Google sheet is shown by the screen; this receives its outcome. */
    fun onGoogleResult(tokenResult: Result<String>) {
        tokenResult.fold(
            onSuccess = ::authenticate,
            onFailure = { _state.value = FormState(error = it.message ?: "Google sign-in failed") },
        )
    }

    private fun authenticate(idToken: String) {
        viewModelScope.launch {
            _state.value = FormState(submitting = true)
            
            // On success the session token is saved by the auth repository.
            _state.value = when (val r = auth.signInWithGoogle(idToken)) {
                is ApiResult.Success -> FormState(result = r.data)
                is ApiResult.Failure -> FormState(error = r.error.message)
            }
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { SignInViewModel(c.authRepository) } }
    }
}

/**
 * Entry screen for signed-out users. The Google Credential Manager sheet needs an Activity context,
 * so it is launched from the composable and its result is handed to the ViewModel.
 */
@Composable
fun SignInScreen(container: AppContainer) {
    val vm: SignInViewModel = screenViewModel(SignInViewModel.factory(container))
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SignInContent(
        state = state,
        onSignInClick = { scope.launch { vm.onGoogleResult(container.googleSignInHelper.getIdToken(context)) } },
    )
}

@Composable
fun SignInContent(
    state: FormState<User>,
    onSignInClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.meetfair_logo),
                contentDescription = "MeetFair Logo",
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                enabled = !state.submitting,
                onClick = onSignInClick,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TealPrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag(TestTags.SIGN_IN_BUTTON),
            ) {
                Text(
                    text = "G  Sign in with Google",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            state.error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(TestTags.ERROR_TEXT),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SignInScreenPreview() {
    CPEN321ApplicationTheme {
        SignInContent(
            state = FormState(),
            onSignInClick = {},
        )
    }
}
