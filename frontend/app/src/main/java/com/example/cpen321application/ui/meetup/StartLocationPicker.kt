package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.Dimens
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

/** Full-screen map keeps pan gestures separate from the participation form's scroll gestures. */
@Composable
internal fun StartLocationPicker(
    initialLat: Double?,
    initialLng: Double?,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double, String) -> Unit,
) {
    val initial = LatLng(initialLat ?: 49.2606, initialLng ?: -123.246)
    val marker = rememberMarkerState(position = initial)
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initial, 15f)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(initialLat != null && initialLng != null) }
    var poiName by rememberSaveable { mutableStateOf("") }
    var poiLat by rememberSaveable { mutableStateOf(Double.NaN) }
    var poiLng by rememberSaveable { mutableStateOf(Double.NaN) }
    val selectedName = if (marker.position.latitude == poiLat && marker.position.longitude == poiLng) poiName else ""

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
            ) {
                Text("Choose your starting location", style = MaterialTheme.typography.titleLarge)
                Text("Pan or zoom the map, then tap your starting point. You can also drag the marker.")
                if (BuildConfig.MAPS_CONFIGURED) {
                    GoogleMap(
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag(TestTags.LOCATION_MAP),
                        cameraPositionState = camera,
                        onMapLoaded = { mapLoaded = true },
                        onMapClick = {
                            marker.position = it
                            selected = true
                            poiName = ""
                        },
                        onPOIClick = {
                            marker.position = it.latLng
                            poiName = it.name
                            poiLat = it.latLng.latitude
                            poiLng = it.latLng.longitude
                            selected = true
                        },
                    ) {
                        Marker(state = marker, draggable = true, title = selectedName.ifBlank { "Starting location" })
                    }
                    if (selectedName.isNotBlank()) Text(selectedName)
                    // A dragged marker counts as an explicit selection, as does a map tap.
                    Button(
                        enabled = mapLoaded && (selected || marker.position != initial) && !marker.isDragging,
                        modifier = Modifier.testTag(TestTags.LOCATION_CONFIRM),
                        onClick = { onConfirm(marker.position.latitude, marker.position.longitude, selectedName.take(120)) },
                    ) { Text("Use this location") }
                } else {
                    Text("Map is unavailable. Please configure the Android Maps key and rebuild the app.")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}
