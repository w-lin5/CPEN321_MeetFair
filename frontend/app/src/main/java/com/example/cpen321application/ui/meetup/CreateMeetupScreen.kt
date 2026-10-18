package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.example.cpen321application.ui.screenViewModel
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
            _state.value = FormState(error = "Time must look like $TIME_PATTERN, e.g. 2026-11-05T14:30")
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

    LaunchedEffect(state.result) { state.result?.let { onCreated(it.id) } }

    ScreenColumn("New meetup", onBack) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.MEETUP_TITLE_INPUT),
        )
        OutlinedTextField(
            value = time,
            onValueChange = { time = it },
            label = { Text("Time ($TIME_PATTERN, device time zone)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.MEETUP_TIME_INPUT),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }
        Button(
            onClick = { vm.submit(title, time) },
            enabled = !state.submitting && title.isNotBlank(),
            modifier = Modifier.testTag(TestTags.CREATE_MEETUP_SUBMIT),
        ) { Text("Create") }
    }
}
