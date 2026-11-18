package com.example.cpen321application.ui.meetup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.cpen321application.R
import com.example.cpen321application.model.MeetupTrip
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.VotesSnapshot
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.StatusGreenLight
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary

@Composable
private fun MeetupDirectionsButton(winner: PlaceCandidate) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val destination = Uri.encode("${winner.name}, ${winner.address}")
            val webUrl = "https://www.google.com/maps/dir/?api=1&destination=$destination"
            val preferred = if (winner.lat != 0.0 || winner.lng != 0.0) {
                "geo:0,0?q=${winner.lat},${winner.lng}(${Uri.encode(winner.name)})".toUri()
            } else {
                webUrl.toUri()
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, preferred))
            } catch (_: ActivityNotFoundException) {
                // No maps app handles geo: links, so fall back to the browser
                context.startActivity(Intent(Intent.ACTION_VIEW, webUrl.toUri()))
            }
        },
        shape = CircleShape,
        border = BorderStroke(1.dp, TealPrimary),
        modifier = Modifier.testTag(TestTags.OPEN_DIRECTIONS_BUTTON),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = DirectionsArrowIcon,
                contentDescription = null,
                tint = TealPrimary,
                modifier = Modifier.size(16.dp),
            )
            Text(text = "Directions", fontWeight = FontWeight.Bold, color = TealPrimary)
        }
    }
}

@Composable
internal fun MeetupMeetingSpotCard(winner: PlaceCandidate?) {
    MeetFairCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.meetfair_map_meetup),
                contentDescription = "Meetup map graphic",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "MEETING SPOT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.2.sp,
                    )
                    Text(
                        text = winner?.name ?: "Pending selection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = TealPrimary,
                        modifier = Modifier.testTag(TestTags.MEETUP_WINNER_TEXT),
                    )
                }
                if (winner != null) MeetupDirectionsButton(winner)
            }
        }
    }
}

@Composable
internal fun MeetupNavCard(onOpenLiveLocation: () -> Unit, onOpenVoting: () -> Unit) {
    MeetFairCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            MeetupNavRow(
                icon = Icons.Default.LocationOn,
                label = "View live locations",
                onClick = onOpenLiveLocation,
                testTag = null,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            MeetupNavRow(
                icon = BarChartIcon,
                label = "See voting results",
                onClick = onOpenVoting,
                testTag = TestTags.OPEN_VOTING_BUTTON,
            )
        }
    }
}

@Composable
private fun MeetupNavRow(icon: ImageVector, label: String, onClick: () -> Unit, testTag: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = TealPrimary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StatusInfoCard(icon: ImageVector, title: String, subtitle: String, titleTag: String) {
    MeetFairCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = TealContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                    modifier = Modifier.testTag(titleTag),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun MeetupSetupStatusCard(submitted: Boolean, participantCount: Int) {
    StatusInfoCard(
        icon = LockIcon,
        title = if (submitted) "Your details are in" else "You have not submitted your details",
        subtitle = "$participantCount member(s) have submitted their details.",
        titleTag = TestTags.PARTICIPATION_STATUS_TEXT,
    )
}

@Composable
internal fun MeetupVotingStatusCard(votes: VotesSnapshot?) {
    val myVote = votes?.myVote
    StatusInfoCard(
        icon = BarChartIcon,
        title = when {
            myVote?.locked == true -> "Your vote is in"
            myVote != null -> "Vote selected (not locked yet)"
            else -> "You have not voted yet"
        },
        subtitle = "${votes?.tally?.lockedCount ?: 0} member(s) have locked their vote.",
        titleTag = TestTags.VOTING_STATUS_TEXT,
    )
}

@Composable
internal fun MeetupTripStatusCard(trip: MeetupTrip) {
    if (trip.hasArrived) MeetupTripArrivedCard(trip) else MeetupTripOnWayCard()
}

@Composable
private fun MeetupTripArrivedCard(trip: MeetupTrip) {
    MeetFairCard(containerColor = StatusGreenLight, borderWidth = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Surface(shape = CircleShape, color = StatusGreen, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = FlagIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "You arrived",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = StatusGreen,
                    )
                    Text(
                        text = "Actual commute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formatTripDuration(trip.actualSeconds ?: 0),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = StatusGreen,
            )
        }
    }
}

@Composable
private fun MeetupTripOnWayCard() {
    MeetFairCard(containerColor = TealContainer, borderWidth = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = TealPrimary, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = TimerIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "On your way",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
                Text(
                    text = "We're timing your commute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
