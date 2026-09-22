package com.example.childsafetyimpl.ux.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.ui.components.DetailHero
import com.example.childsafetyimpl.ui.components.HeroStatRow
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SafetyCard
import com.example.childsafetyimpl.ui.components.StatusChip
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.brandGradient

/**
 * The frame every protection screen shares.
 *
 * The hero always carries the protection's own state chip, so a screen can never describe a
 * protection as doing something while the chip beside it says the protection is off. Keeping that
 * pairing in the scaffold rather than in each screen is what makes it structurally true instead of
 * a convention each screen has to remember.
 */
@Composable
fun ProtectionScaffold(
    status: ProtectionStatus,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    heroStats: List<Pair<String, String>> = emptyList(),
    brush: Brush = brandGradient,
    /**
     * What to do about the grant this protection is missing.
     *
     * Passed in rather than assumed, because the answer is not the same for every grant. Usage
     * access is granted once in Settings and belongs on the permissions list; screen capture is
     * asked for again every session and can only be started from the screen that uses it. Sending
     * a parent to the permissions list for the second one is how they end up going in circles.
     */
    onGrantMissing: ((SafetyPermission) -> Unit)? = null,
    gateActionLabel: String = "Open the setting",
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SafetyPalette.Canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            DetailHero(
                title = status.feature.title,
                subtitle = status.feature.summary,
                onBack = onBack,
                brush = brush,
                trailing = {
                    Switch(
                        checked = status.enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0x59FFFFFF),
                            checkedBorderColor = Color(0x40FFFFFF),
                            uncheckedThumbColor = Color(0xB8FFFFFF),
                            uncheckedTrackColor = Color(0x26FFFFFF),
                            uncheckedBorderColor = Color(0x33FFFFFF),
                        ),
                    )
                },
                belowTitle = {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        StatusChip(status.state)
                    }
                    if (heroStats.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        HeroStatRow(heroStats)
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (status.missingRequired.isNotEmpty()) {
                    val missing = status.missingRequired.first()
                    PermissionGateCard(
                        permission = missing,
                        onGrant = { onGrantMissing?.invoke(missing) },
                        interactive = onGrantMissing != null,
                        actionLabel = gateActionLabel,
                    )
                }

                content()

                HowItWorksCard(status)
            }

            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/**
 * What the protection actually does, in the screen's own words.
 *
 * Every protection screen carries one. A parent deciding whether to leave something switched on
 * deserves to know the mechanism, not just the promise, and burying it in a help centre nobody
 * opens is the same as not saying it.
 */
@Composable
fun HowItWorksCard(status: ProtectionStatus, modifier: Modifier = Modifier) {
    SafetyCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Rounded.Info,
                tint = SafetyPalette.IndigoDeep,
                container = SafetyPalette.IndigoSoft,
                size = 34,
            )
            Spacer(Modifier.size(11.dp))
            Text(
                text = "How this works",
                style = MaterialTheme.typography.titleMedium,
                color = SafetyPalette.Ink,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = status.feature.mechanism,
            style = MaterialTheme.typography.bodyMedium,
            color = SafetyPalette.Slate,
        )

        if (status.missingOptional.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Could be stronger",
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
            )
            Spacer(Modifier.height(6.dp))
            status.missingOptional.forEach { permission ->
                BulletLine("${permission.title}: ${permission.enables}")
            }
        }
    }
}

@Composable
fun BulletLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(bottom = 7.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp, end = 9.dp)
                .size(5.dp)
                .background(SafetyPalette.Mist, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
    }
}

/** Result banner: what happened, in the words of whatever produced it. */
@Composable
fun OutcomeCard(
    headline: String,
    detail: String,
    tone: OutcomeTone,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    val (icon, content, container) = when (tone) {
        OutcomeTone.SUCCESS ->
            Triple(Icons.Rounded.CheckCircle, Color(0xFF0E8F5E), SafetyPalette.MintSoft)
        OutcomeTone.BLOCKED ->
            Triple(Icons.Rounded.Lock, Color(0xFFC13438), SafetyPalette.DangerSoft)
        OutcomeTone.WARNING ->
            Triple(Icons.Rounded.ErrorOutline, Color(0xFFB26A00), SafetyPalette.WarningSoft)
        OutcomeTone.NEUTRAL ->
            Triple(Icons.Rounded.Info, SafetyPalette.IndigoDeep, SafetyPalette.IndigoSoft)
    }

    SafetyCard(modifier = modifier, background = container, borderColor = Color.Transparent) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(21.dp))
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Ink.copy(alpha = 0.78f),
                )
                if (footnote != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = footnote,
                        style = MaterialTheme.typography.labelSmall,
                        color = SafetyPalette.Slate,
                    )
                }
            }
        }
    }
}

enum class OutcomeTone { SUCCESS, BLOCKED, WARNING, NEUTRAL }

/**
 * Shown in place of results when a grant the protection needs is missing.
 *
 * This card is the reason the app can be trusted. Without it, every screen backed by usage stats
 * would show a confident zero, and a zero is indistinguishable from "your child used nothing".
 */
@Composable
fun PermissionGateCard(
    permission: SafetyPermission,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    actionLabel: String = "Open the setting",
) {
    SafetyCard(
        modifier = modifier,
        background = SafetyPalette.WarningSoft,
        borderColor = Color.Transparent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Rounded.Lock,
                tint = Color(0xFFB26A00),
                container = Color(0x33FFFFFF),
            )
            Spacer(Modifier.size(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${permission.title} is needed",
                    style = MaterialTheme.typography.titleMedium,
                    color = SafetyPalette.Ink,
                )
                Text(
                    text = permission.grantMechanism.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB26A00),
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        Text(
            text = permission.withoutIt,
            style = MaterialTheme.typography.bodyMedium,
            color = SafetyPalette.Ink.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Granting it gives this app: ${permission.grants}",
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
        if (interactive) {
            Spacer(Modifier.height(15.dp))
            PrimaryButton(
                text = actionLabel,
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = when (permission.grantMechanism) {
                    // Naming the permissions list for a per-session consent sends a parent to a
                    // screen that cannot grant it.
                    SafetyPermission.GrantMechanism.CONSENT_DIALOG ->
                        "Android asks for this again every session. Start it from this screen."
                    SafetyPermission.GrantMechanism.DEVICE_PROVISIONING ->
                        "This cannot be granted on a device set up the usual way."
                    else -> "Grant it from the Permissions screen."
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB26A00),
            )
        }
    }
}

/** Section card used inside protection screens, with a count when there is one worth showing. */
@Composable
fun ProtectionSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SafetyCard(modifier = modifier, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconBadge(
                    icon = icon,
                    tint = SafetyPalette.Indigo,
                    container = SafetyPalette.IndigoSoft,
                    size = 34,
                )
                Spacer(Modifier.size(11.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = SafetyPalette.Ink,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = SafetyPalette.Slate,
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        content()
    }
}

/** A labelled switch row, the control every protection screen is mostly made of. */
@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) SafetyPalette.Ink else SafetyPalette.Mist,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = SafetyPalette.Slate,
            )
        }
        Spacer(Modifier.size(14.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SafetyPalette.Indigo,
                checkedBorderColor = SafetyPalette.Indigo,
            ),
        )
    }
}
