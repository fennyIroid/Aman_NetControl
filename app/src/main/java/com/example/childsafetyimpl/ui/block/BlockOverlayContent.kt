package com.example.childsafetyimpl.ui.block

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.platform.enforcement.BlockDecision
import com.example.childsafetyimpl.platform.enforcement.BlockReason
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.illustration.PausedMoment
import com.example.childsafetyimpl.ui.theme.ChildSafetyTheme
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.calmGradient

/**
 * The screen the child sees when a rule applies.
 *
 * Written for a child rather than for a log. It names the rule in plain words, says when the app
 * comes back, and gives a way to ask a parent instead of leaving the only option as working around
 * it. The tone is deliberately calm: a block screen that reads as punishment is the fastest route
 * to a child who spends their afternoon defeating the app.
 */
@Composable
fun BlockOverlayContent(
    decision: BlockDecision,
    onAskParent: () -> Unit,
    onClose: () -> Unit,
    /** The way out when a VPN is the reason, which only Settings can offer. */
    onOpenVpnSettings: () -> Unit = {},
) {
    ChildSafetyTheme {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        val entrance by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = SafetyMotion.screenEnter(),
            label = "blockEntrance",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(calmGradient)
                .alpha(entrance),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .scale(0.94f + entrance * 0.06f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PausedMoment()

                Spacer(Modifier.height(26.dp))

                Text(
                    text = headlineFor(decision),
                    style = MaterialTheme.typography.displaySmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = explanationFor(decision),
                    style = MaterialTheme.typography.bodyLarge,
                    color = androidx.compose.ui.graphics.Color(0xE0FFFFFF),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(34.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    // When a VPN is the reason, the useful thing to offer is the way to end it.
                    val vpnActive = decision.reason == BlockReason.VpnActive
                    PrimaryButton(
                        text = if (vpnActive) "Turn off the VPN" else "Ask a parent",
                        onClick = if (vpnActive) onOpenVpnSettings else onAskParent,
                        icon = if (vpnActive) Icons.Rounded.VpnKey else Icons.Rounded.ChatBubbleOutline,
                        modifier = Modifier.fillMaxWidth(),
                        brush = androidx.compose.ui.graphics.SolidColor(
                            androidx.compose.ui.graphics.Color(0x33FFFFFF),
                        ),
                    )
                    // Always dismissible. An overlay a child cannot leave is a trap, and Play
                    // treats an undismissible window over another app as disruptive behaviour.
                    // Closing leaves the blocked app rather than only taking this screen away:
                    // dropping the child back into the app they were told they cannot use reads
                    // as the rule having been withdrawn. The pause is the exception: the app is
                    // not refused, only offline, so the child is handed back to it.
                    SecondaryButton(
                        text = if (decision.reason == BlockReason.InternetPaused) "OK" else "Close",
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                        contentColor = SafetyPalette.Indigo,
                    )
                }
            }
        }
    }
}

private fun headlineFor(decision: BlockDecision): String = when (decision.reason) {
    is BlockReason.DailyLimitReached -> "Time's up on ${decision.appLabel}"
    is BlockReason.CategoryBudgetReached -> "That's the time for today"
    is BlockReason.ScheduleWindow -> "${decision.appLabel} is on a break"
    is BlockReason.BedtimeWindow -> "It's wind-down time"
    BlockReason.AlwaysBlocked -> "${decision.appLabel} is switched off"
    BlockReason.VpnApp -> "VPN apps are switched off"
    BlockReason.VpnActive -> "A VPN is turned on"
    BlockReason.CloneApp -> "App copying is switched off"
    is BlockReason.RoutineRestriction -> "It's ${decision.reason.let { (it as BlockReason.RoutineRestriction).routineName }} time"
    BlockReason.InternetPaused -> "The internet is paused"
}

private fun explanationFor(decision: BlockDecision): String = when (val reason = decision.reason) {
    is BlockReason.DailyLimitReached ->
        "You used all ${reason.limitMinutes} minutes today. It opens again tomorrow morning."

    is BlockReason.CategoryBudgetReached ->
        "${reason.category.label} has a shared ${reason.limitMinutes} minute budget, " +
            "and it's all used up for today."

    is BlockReason.ScheduleWindow ->
        "It comes back at ${TimeFormat.formatMinuteOfDay(reason.toMinuteOfDay)}."

    is BlockReason.BedtimeWindow ->
        "Screens are off until ${TimeFormat.formatMinuteOfDay(reason.toMinuteOfDay)} " +
            "so you get a proper night's sleep."

    BlockReason.AlwaysBlocked ->
        "A parent turned this one off. You can ask them to change it."

    BlockReason.VpnApp ->
        "This device keeps its own safe connection, so VPN apps stay closed."

    BlockReason.VpnActive ->
        "Apps stay closed while a VPN is on. Turn it off in Settings and everything comes " +
            "straight back."

    BlockReason.CloneApp ->
        "Making a second copy of an app gets around the settings a parent chose."

    // Named rather than timed. "Blocked until 3pm" is a fact; "it's School time until 3pm" is a
    // reason, and a child who understands the reason argues with the rule instead of the phone.
    is BlockReason.RoutineRestriction ->
        "${reason.routineName} runs until " +
            "${TimeFormat.formatMinuteOfDay(reason.endsAtMinuteOfDay)}. " +
            "This app comes back on its own after that."

    // The single most important string in the child-facing product. Without it this app is a
    // browser showing a connection error, and a child concludes the phone is broken rather than
    // that a parent made a decision. Nothing is blamed and nothing is scolded: what happened,
    // that it is deliberate, and that it ends.
    BlockReason.InternetPaused ->
        "A parent paused the internet for now. Nothing is broken, and anything that works " +
            "without the internet still works. It comes back on when they turn it back on."
}
