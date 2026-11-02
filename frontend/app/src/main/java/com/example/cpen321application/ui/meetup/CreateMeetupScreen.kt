package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.groups.dashedBorder
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TIME_PATTERN = "yyyy-MM-dd'T'HH:mm"
private const val DEFAULT_LEAD_HOURS = 1L
private val TIME_INPUT_FORMAT = DateTimeFormatter.ofPattern(TIME_PATTERN)
private val TIME_DISPLAY_FORMAT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** Device-local "yyyy-MM-ddTHH:mm" text to an ISO-8601 instant; null if the text is malformed. */
internal fun localInputToIso(text: String): String? = try {
    LocalDateTime.parse(text.trim(), TIME_INPUT_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toString()
} catch (_: DateTimeParseException) {
    null
}

/** ISO-8601 instant to a readable date and time in the device time zone; falls back to the raw text. */
internal fun isoToDisplay(iso: String): String = try {
    TIME_DISPLAY_FORMAT.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
} catch (_: DateTimeParseException) {
    iso
}

private fun defaultTimeInput(): String =
    LocalDateTime.now().plusHours(DEFAULT_LEAD_HOURS).truncatedTo(ChronoUnit.MINUTES).format(TIME_INPUT_FORMAT)

/** Creates a meetup in a group; the created meetup is exposed through [FormState.result]. */
class CreateMeetupViewModel(private val meetups: MeetupRepository, private val groupId: String) : ViewModel() {
    private val _state = MutableStateFlow(FormState<Meetup>())
    val state: StateFlow<FormState<Meetup>> = _state.asStateFlow()

    fun submit(title: String, timeInput: String) {
        if (_state.value.submitting) return

        val iso = localInputToIso(timeInput)
        
        if (iso == null) {
            _state.value = FormState(error = "Please choose a valid meeting date and time.")
        } else if (!Instant.parse(iso).isAfter(Instant.now())) {
            _state.value = FormState(error = "Meeting time must be later than now. Please choose a new time.")
        } else {
            viewModelScope.launch {
                _state.value = FormState(submitting = true)
                _state.value = meetups.create(groupId, title.trim(), iso).toFormState()
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, groupId: String) =
            viewModelFactory { initializer { CreateMeetupViewModel(c.meetupRepository, groupId) } }
    }
}

@Composable
fun CreateMeetupScreen(container: AppContainer, groupId: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val vm: CreateMeetupViewModel = screenViewModel(CreateMeetupViewModel.factory(container, groupId))
    val state by vm.state.collectAsState()
    var title by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf(defaultTimeInput()) }
    var choosingTime by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.result) { state.result?.let { onCreated(it.id) } }

    if (choosingTime) {
        MeetingDateTimePicker(
            initial = LocalDateTime.parse(time, TIME_INPUT_FORMAT),
            onDismiss = { choosingTime = false },
            onConfirm = { time = it.format(TIME_INPUT_FORMAT); choosingTime = false },
        )
    }

    ScreenColumn(title = "", onBack = onBack) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .dashedBorder(strokeWidth = 1.5.dp, color = TealPrimary, cornerRadius = 24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = TealContainer),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "New meetup",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = TealPrimary,
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Title", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = TealPrimary.copy(alpha = 0.6f),
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.MEETUP_TITLE_INPUT),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Meeting date and time",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TealPrimary,
                    )

                    OutlinedButton(
                        onClick = { choosingTime = true },
                        enabled = !state.submitting,
                        shape = CircleShape,
                        border = BorderStroke(1.5.dp, TealPrimary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag(TestTags.MEETUP_TIME_INPUT),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = TealPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = TIME_DISPLAY_FORMAT.format(LocalDateTime.parse(time, TIME_INPUT_FORMAT)),
                                fontWeight = FontWeight.Bold,
                                color = TealPrimary,
                            )
                        }
                    }
                }

                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(TestTags.ERROR_TEXT),
                    )
                }

                Button(
                    onClick = { vm.submit(title, time) },
                    enabled = !state.submitting && title.isNotBlank(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RosePrimary,
                        contentColor = Color.White,
                        disabledContainerColor = RosePrimary.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(TestTags.CREATE_MEETUP_SUBMIT),
                ) {
                    Text(
                        text = "Create meetup →",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
