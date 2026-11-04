package com.example.cpen321application.ui.meetup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PointOfInterest
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
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
    BackHandler(onBack = onDismiss)

    val initial = LatLng(initialLat ?: 49.2606, initialLng ?: -123.246)
    val marker = rememberMarkerState(position = initial)
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initial, 15f)
    }
    var poiName by rememberSaveable { mutableStateOf("") }
    var poiLat by rememberSaveable { mutableStateOf(Double.NaN) }
    var poiLng by rememberSaveable { mutableStateOf(Double.NaN) }
    val selectedName = if (marker.position.latitude == poiLat && marker.position.longitude == poiLng) poiName else ""

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PickerHeader()

            PickerMapArea(
                modifier = Modifier.fillMaxWidth().weight(1f),
                marker = marker,
                camera = camera,
                selectedName = selectedName,
                onMapClick = {
                    marker.position = it
                    poiName = ""
                },
                onPoiClick = {
                    marker.position = it.latLng
                    poiName = it.name
                    poiLat = it.latLng.latitude
                    poiLng = it.latLng.longitude
                },
            )

            if (selectedName.isNotBlank()) SelectedPlaceChip(selectedName)

            PickerActions(
                confirmEnabled = !marker.isDragging,
                onConfirm = { onConfirm(marker.position.latitude, marker.position.longitude, selectedName.take(120)) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun PickerHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Choose starting location",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = TealPrimary,
        )
        Text(
            text = "Pan or zoom the map, then tap your starting point. You can also drag the marker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PickerMapArea(
    modifier: Modifier,
    marker: MarkerState,
    camera: CameraPositionState,
    selectedName: String,
    onMapClick: (LatLng) -> Unit,
    onPoiClick: (PointOfInterest) -> Unit,
) {
    val context = LocalContext.current
    val playServicesAvailable = remember(context) {
        try {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Throwable) {
            false
        }
    }

    if (LocalInspectionMode.current) {
        // Placeholder map view for Compose Previews in Android Studio
        Surface(
            modifier = modifier.testTag(TestTags.LOCATION_MAP),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = "Map Preview",
                modifier = Modifier.padding(Dimens.ScreenPadding),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else if (BuildConfig.MAPS_CONFIGURED && playServicesAvailable) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, TealContainer),
        ) {
            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TestTags.LOCATION_MAP),
                cameraPositionState = camera,
                onMapClick = onMapClick,
                onPOIClick = onPoiClick,
            ) {
                Marker(
                    state = marker,
                    draggable = true,
                    title = selectedName.ifBlank { "Starting location" },
                )
            }
        }
    } else {
        MapUnavailableCard(modifier, marker.position)
    }
}

@Composable
private fun MapUnavailableCard(modifier: Modifier, position: LatLng) {
    Card(
        modifier = modifier.testTag(TestTags.LOCATION_MAP),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = TealContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = RosePrimary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = "Starting location point selected (${position.latitude}, ${position.longitude})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
            )
            Text(
                text = "Map tiles are unconfigured, but you can confirm these starting coordinates below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SelectedPlaceChip(name: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = TealContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = RosePrimary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
            )
        }
    }
}

@Composable
private fun PickerActions(confirmEnabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Button(
        enabled = confirmEnabled,
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
            .testTag(TestTags.LOCATION_CONFIRM),
        onClick = onConfirm,
    ) {
        Text(
            text = "Use this location",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    OutlinedButton(
        onClick = onDismiss,
        shape = CircleShape,
        border = BorderStroke(1.dp, TealPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(
            text = "Cancel",
            fontWeight = FontWeight.Bold,
            color = TealPrimary,
        )
    }
}

