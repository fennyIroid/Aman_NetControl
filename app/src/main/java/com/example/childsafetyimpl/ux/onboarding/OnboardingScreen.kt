package com.example.childsafetyimpl.ux.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SafetyCard
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.illustration.ShieldPulse
import com.example.childsafetyimpl.ui.illustration.ShieldState
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import com.example.childsafetyimpl.ui.theme.brandGradient

/**
 * Three short steps: what this build is, who it protects, and what it will ask for.
 *
 * The first step says what the app will do and what it will need. Leading with the permissions
 * rather than burying them is deliberate: every one of them is a serious grant, and a parent who
 * discovers that later is right to be uneasy about it.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onSetName: (String) -> Unit,
    onSetAge: (Int) -> Unit,
    onSetAvatar: (Int) -> Unit,
    onSetDevice: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(brandGradient),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            repeat(OnboardingUiState.LAST_STEP + 1) { index ->
                val width by animateDpAsState(
                    targetValue = if (index == state.step) 30.dp else 9.dp,
                    animationSpec = SafetyMotion.value(),
                    label = "stepDot",
                )
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index <= state.step) Color.White else Color(0x4DFFFFFF),
                        ),
                )
            }
        }

        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(SafetyMotion.screenEnter()) { it / 3 } +
                        fadeIn(SafetyMotion.contentFade())) togetherWith
                        (slideOutHorizontally(SafetyMotion.screenExit()) { -it / 3 } +
                            fadeOut(SafetyMotion.contentFade()))
                } else {
                    (slideInHorizontally(SafetyMotion.screenEnter()) { -it / 3 } +
                        fadeIn(SafetyMotion.contentFade())) togetherWith
                        (slideOutHorizontally(SafetyMotion.screenExit()) { it / 3 } +
                            fadeOut(SafetyMotion.contentFade()))
                }
            },
            modifier = Modifier.weight(1f),
            label = "onboardingStep",
        ) { step ->
            when (step) {
                0 -> IntroStep(state)
                1 -> ChildStep(state, onSetName, onSetAge, onSetAvatar)
                else -> DeviceStep(state, onSetDevice)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            PrimaryButton(
                text = if (state.isLastStep) "Open the dashboard" else "Continue",
                onClick = onNext,
                icon = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                iconAtEnd = true,
                enabled = state.canContinue,
                brush = androidx.compose.ui.graphics.SolidColor(Color.White),
                contentColor = SafetyPalette.Indigo,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.step > 0) {
                SecondaryButton(
                    text = "Back",
                    onClick = onBack,
                    contentColor = SafetyPalette.Indigo,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun IntroStep(state: OnboardingUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShieldPulse(state = ShieldState.ACTIVE, size = 132.dp, coverage = 0.72f)

        Spacer(Modifier.height(30.dp))

        Text(
            text = "A safety layer, honestly measured",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(13.dp))

        Text(
            text = "Nine protections that run on this device. Everything is judged here, " +
                "nothing is uploaded, and where Android limits what is possible the app says so " +
                "rather than pretending.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xE0FFFFFF),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(26.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            IntroStat(state.protectionCount.toString(), "protections")
            IntroStat(state.primaryCount.toString(), "first priority")
            IntroStat(state.grantCount.toString(), "permissions")
        }
    }
}

@Composable
private fun IntroStat(value: String, label: String) {
    Column(
        modifier = Modifier
            .clip(SafetyRadius.tile)
            .background(Color(0x24FFFFFF))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xB8FFFFFF),
        )
    }
}

@Composable
private fun ChildStep(
    state: OnboardingUiState,
    onSetName: (String) -> Unit,
    onSetAge: (Int) -> Unit,
    onSetAvatar: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Who is this device for?",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = "Stored on this device only. Nothing is uploaded and nothing leaves the " +
                "handset.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xCCFFFFFF),
        )

        Spacer(Modifier.height(26.dp))

        SafetyCard {
            OutlinedTextField(
                value = state.childName,
                onValueChange = onSetName,
                label = { Text("Child's first name") },
                singleLine = true,
                shape = SafetyRadius.field,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SafetyPalette.Indigo,
                    unfocusedBorderColor = SafetyPalette.Line,
                ),
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Age ${state.childAge}",
                style = MaterialTheme.typography.titleMedium,
                color = SafetyPalette.Ink,
            )
            Slider(
                value = state.childAge.toFloat(),
                onValueChange = { onSetAge(it.toInt()) },
                valueRange = 4f..17f,
                steps = 12,
                colors = SliderDefaults.colors(
                    thumbColor = SafetyPalette.Indigo,
                    activeTrackColor = SafetyPalette.Indigo,
                    inactiveTrackColor = SafetyPalette.Line,
                ),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Pick an avatar",
                style = MaterialTheme.typography.titleMedium,
                color = SafetyPalette.Ink,
            )
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                val avatars = listOf(
                    SafetyPalette.Apricot to "🦊",
                    SafetyPalette.Mint to "🐢",
                    SafetyPalette.Lavender to "🦉",
                    SafetyPalette.Sky to "🐬",
                    SafetyPalette.Coral to "🦁",
                )
                avatars.forEachIndexed { index, (color, emoji) ->
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == state.avatarIndex) color
                                else color.copy(alpha = 0.22f),
                            )
                            .clickable { onSetAvatar(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceStep(state: OnboardingUiState, onSetDevice: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "What this will ask for",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = "Most protections need a permission granted in Android Settings rather than a " +
                "dialog. Nothing is switched on until you grant it, and the permission centre " +
                "explains each one before you do.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xCCFFFFFF),
        )

        Spacer(Modifier.height(26.dp))

        SafetyCard {
            OutlinedTextField(
                value = state.deviceLabel,
                onValueChange = onSetDevice,
                label = { Text("Device name") },
                singleLine = true,
                shape = SafetyRadius.field,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SafetyPalette.Indigo,
                    unfocusedBorderColor = SafetyPalette.Line,
                ),
            )

            Spacer(Modifier.height(18.dp))

            listOf(
                "Usage Access" to "Screen time, limits and the current app",
                "Display over other apps" to "The block screen when a limit is reached",
                "VPN consent" to "Filtering unsafe destinations device-wide",
                "Notification Access" to "Communication signals, never message text",
                "Accessibility" to "Recognising Shorts, Status and AI screens",
            ).forEachIndexed { index, (title, detail) ->
                if (index > 0) Spacer(Modifier.height(13.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(SafetyPalette.IndigoMuted),
                    )
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = SafetyPalette.Ink,
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = SafetyPalette.Slate,
                        )
                    }
                }
            }
        }
    }
}
