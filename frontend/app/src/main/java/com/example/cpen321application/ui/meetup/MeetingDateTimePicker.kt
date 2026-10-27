package com.example.cpen321application.ui.meetup

import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cpen321application.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Material's calendar dates are UTC-midnight values, not instants in the device timezone.
internal fun calendarDateMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun calendarDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun isFutureMeetingTime(time: LocalDateTime, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
    time.atZone(zone).toInstant().isAfter(now)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeetingDateTimePicker(initial: LocalDateTime, onDismiss: () -> Unit, onConfirm: (LocalDateTime) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var date by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    if (step == 0) {
        val calendar = rememberDatePickerState(
            initialSelectedDateMillis = calendarDateMillis(LocalDate.parse(date)),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !calendarDate(utcTimeMillis).isBefore(LocalDate.now())
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(enabled = calendar.selectedDateMillis?.let { !calendarDate(it).isBefore(LocalDate.now()) } == true,
                    onClick = {
                        calendar.selectedDateMillis?.let { date = calendarDate(it).toString(); step = 1 }
                    }, modifier = Modifier.testTag("meeting_date_next")) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = calendar, showModeToggle = false)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Choose time") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                    Text(LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                    Text("24-hour time · ${java.time.ZoneId.systemDefault().id}")
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                        TimeWheel("Hour", 23, hour, "meeting_hour_wheel") { hour = it; error = null }
                        TimeWheel("Minute", 59, minute, "meeting_minute_wheel") { minute = it; error = null }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selected = LocalDate.parse(date).atTime(hour, minute)
                    if (isFutureMeetingTime(selected)) onConfirm(selected)
                    else error = "Please choose a time later than now."
                },
                    modifier = Modifier.testTag("meeting_time_confirm")) { Text("Use this time") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { step = 0 }) { Text("Back") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun TimeWheel(label: String, maximum: Int, value: Int, tag: String, onChange: (Int) -> Unit) {
    Column {
        Text(label)
        AndroidView(
            modifier = Modifier.width(110.dp).height(160.dp).testTag(tag),
            factory = { context ->
                NumberPicker(context).apply {
                    minValue = 0
                    maxValue = maximum
                    wrapSelectorWheel = true
                    displayedValues = Array(maximum + 1) { String.format(Locale.getDefault(), "%02d", it) }
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    contentDescription = label
                    this.value = value
                    setOnValueChangedListener { _, _, newValue -> onChange(newValue) }
                }
            },
            update = { if (it.value != value) it.value = value },
        )
    }
}
