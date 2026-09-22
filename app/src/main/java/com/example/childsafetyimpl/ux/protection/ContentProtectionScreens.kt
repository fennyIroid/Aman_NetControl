package com.example.childsafetyimpl.ux.protection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.platform.content.BlurLatency
import com.example.childsafetyimpl.platform.content.BlurMode
import com.example.childsafetyimpl.platform.content.DetectionQuality
import com.example.childsafetyimpl.platform.content.ProtectionLevel
import com.example.childsafetyimpl.platform.content.ValuesCategory
import com.example.childsafetyimpl.platform.content.SubjectCategory
import com.example.childsafetyimpl.ui.components.DetailRow
import com.example.childsafetyimpl.ui.components.FilterPill
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ux.common.BulletLine
import com.example.childsafetyimpl.ux.common.OutcomeCard
import com.example.childsafetyimpl.ux.common.OutcomeTone
import com.example.childsafetyimpl.ux.common.ProtectionScaffold
import com.example.childsafetyimpl.ux.common.ProtectionSection
import com.example.childsafetyimpl.ux.common.SettingSwitchRow

// --- Ad removal -----------------------------------------------------------------------------------

@Composable
fun AdRemovalScreen(viewModel: AdRemovalViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(AdRemovalUiEvent.Back) },
        onToggle = { viewModel.onEvent(AdRemovalUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            state.totalBlocked.toString() to "Ads removed",
            state.hostCount.toString() to "Ad hosts",
            (if (state.tunnelRunning) "On" else "Off") to "Device-wide",
        ),
    ) {
        if (!state.tunnelRunning) {
            OutcomeCard(
                headline = "Ads are removed here, not everywhere",
                detail = "Inside the built-in player and browser, ad requests are refused and ad " +
                    "slots are hidden. Removing ads from other apps needs the filtering tunnel, " +
                    "which is not running.",
                tone = OutcomeTone.WARNING,
                footnote = "Start the tunnel from the VPN restriction screen.",
            )
        } else {
            OutcomeCard(
                headline = "Ads are being removed device-wide",
                detail = "Ad and tracker hosts are refused for every app on this device, and the " +
                    "built-in player and browser strip them at the request level as well.",
                tone = OutcomeTone.SUCCESS,
            )
        }

        ProtectionSection(title = "Settings", icon = Icons.Rounded.Tune) {
            SettingSwitchRow(
                title = "Remove ads",
                description = "Refuses ad and tracker requests and hides the slots they leave.",
                checked = state.enabled,
                onCheckedChange = { viewModel.onEvent(AdRemovalUiEvent.ToggleAdBlock(it)) },
            )
        }

        ProtectionSection(title = "Where ads were removed", icon = Icons.Rounded.Block) {
            DetailRow("In other apps, through the tunnel", state.tunnelAdsBlocked.toString())
            DetailRow("In the built-in player", state.playerAdsBlocked.toString())
            DetailRow("In the built-in browser", state.browserAdsBlocked.toString())
            Spacer(Modifier.height(10.dp))
            DetailRow("Ad hosts refused", state.hostCount.toString())
            DetailRow("Ad request patterns", state.patternCount.toString())
        }

        ProtectionSection(title = "Try it", icon = Icons.Rounded.PlayCircleOutline) {
            Text(
                text = "Both built-in surfaces have ad removal on. Open one and the counters " +
                    "above move.",
                style = MaterialTheme.typography.bodyMedium,
                color = SafetyPalette.Slate,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "Open the safe player",
                onClick = { viewModel.onEvent(AdRemovalUiEvent.OpenPlayer) },
                icon = Icons.Rounded.PlayCircleOutline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(9.dp))
            SecondaryButton(
                text = "Open the safe browser",
                onClick = { viewModel.onEvent(AdRemovalUiEvent.OpenBrowser) },
                icon = Icons.Rounded.Language,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ProtectionSection(title = "What this can and cannot do", icon = Icons.Rounded.Insights) {
            BulletLine(
                "Video ads in the YouTube app are fetched from ad hosts, and refusing those " +
                    "names at the tunnel removes the ad break rather than leaving a gap.",
            )
            BulletLine(
                "An app that serves its ads from the same address as its content cannot be " +
                    "separated at the name level, and those ads still appear.",
            )
            BulletLine(
                "The blocklist is deliberately narrow. A broad one looks impressive and breaks " +
                    "apps in ways a parent cannot diagnose.",
            )
        }
    }
}

// --- Image blurring --------------------------------------------------------------------------------

@Composable
fun ImageBlurringScreen(viewModel: ImageBlurringViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return
    val stats = state.stats

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(ImageBlurringUiEvent.Back) },
        onToggle = { viewModel.onEvent(ImageBlurringUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            stats.imagesCovered.toString() to "Blurred",
            stats.imagesScored.toString() to "Checked",
            "${stats.averageScoringMillis} ms" to "Per image",
        ),
    ) {
        OutcomeCard(
            headline = if (stats.imagesCovered == 0) "Nothing has needed blurring"
            else "${stats.imagesCovered} images blurred",
            detail = "Every image the built-in browser requests is fetched here, scored on this " +
                "device, and only then handed to the page. An image that fails arrives blurred, " +
                "so the original never reaches the screen.",
            tone = if (stats.imagesCovered == 0) OutcomeTone.SUCCESS else OutcomeTone.BLOCKED,
        )

        ProtectionSection(title = "Settings", icon = Icons.Rounded.Tune) {
            SettingSwitchRow(
                title = "Blur unsafe images",
                description = "Off means images are passed through without being checked.",
                checked = state.enabled,
                onCheckedChange = { viewModel.onEvent(ImageBlurringUiEvent.ToggleBlur(it)) },
            )

            Spacer(Modifier.height(14.dp))
            BlurStrengthSlider(
                strength = state.blurStrength,
                onChange = { viewModel.onEvent(ImageBlurringUiEvent.SetStrength(it)) },
            )
        }

        ProtectionSection(title = "What has been checked", icon = Icons.Rounded.HideImage) {
            DetailRow("Images scored", stats.imagesScored.toString())
            DetailRow("Images blurred", stats.imagesCovered.toString())
            DetailRow("Skipped as too large or too small", stats.imagesSkipped.toString())
            DetailRow("Average cost", "${stats.averageScoringMillis} ms per image")
            Spacer(Modifier.height(10.dp))
            DetailRow("Labels mapped to risk", state.mappedLabelCount.toString())
            DetailRow("Of those, body exposure", state.exposureLabelCount.toString())
        }

        ProtectionSection(title = "Try it", icon = Icons.Rounded.Language) {
            Text(
                text = "Open the safe browser and load a page with photographs. The counters " +
                    "above move as images are checked.",
                style = MaterialTheme.typography.bodyMedium,
                color = SafetyPalette.Slate,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "Open the safe browser",
                onClick = { viewModel.onEvent(ImageBlurringUiEvent.OpenBrowser) },
                icon = Icons.Rounded.Language,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ProtectionSection(title = "How images are judged", icon = Icons.Rounded.Insights) {
            BulletLine(
                "A bundled model runs on this device and returns what it recognises. No image is " +
                    "ever uploaded anywhere.",
            )
            BulletLine(
                "A pixel measurement is used only to strengthen a verdict the model already has " +
                    "reason to suspect. On its own it cannot blur anything, which is what stops " +
                    "a portrait or a beach photo being covered for having skin in it.",
            )
            BulletLine(
                "The model recognises swimwear, bathing and adult settings with real confidence. " +
                    "It cannot recognise explicit content as such, and this is stated rather than " +
                    "papered over.",
            )
            BulletLine(
                "Very large images are passed through unchecked rather than stalling the page. " +
                    "That gap is counted above instead of being hidden.",
            )
        }
    }
}

// --- Video blurring ---------------------------------------------------------------------------------

@Composable
fun VideoBlurringScreen(viewModel: VideoBlurringViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return
    val metrics = state.metrics

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != 0 && data != null) {
            viewModel.onEvent(VideoBlurringUiEvent.CaptureGranted(result.resultCode, data))
        }
    }

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(VideoBlurringUiEvent.Back) },
        onToggle = { viewModel.onEvent(VideoBlurringUiEvent.ToggleFeature(it)) },
        // Screen capture is the one grant that cannot be given on the permissions list, so the
        // card offers the consent dialog itself rather than pointing somewhere it is not.
        onGrantMissing = { permission ->
            if (permission == SafetyPermission.SCREEN_CAPTURE) {
                consentLauncher.launch(viewModel.captureConsentIntent())
            } else {
                viewModel.onEvent(VideoBlurringUiEvent.OpenPermissions)
            }
        },
        gateActionLabel = "Start watching video",
        heroStats = listOf(
            metrics.blurEvents.toString() to "Covered",
            metrics.framesAnalysed.toString() to "Frames",
            "${metrics.averageAnalysisMillis} ms" to "Per frame",
        ),
    ) {
        when {
            state.currentlyBlurring -> OutcomeCard(
                headline = "Covering the screen now",
                detail = "Something on screen was judged unsafe and is covered. The cover comes " +
                    "down when the screen moves on, and is re-examined on a timer besides.",
                tone = OutcomeTone.BLOCKED,
            )

            state.capturing -> OutcomeCard(
                headline = "Watching video",
                detail = "Frames are being sampled and judged on this device. Nothing is saved " +
                    "and nothing is uploaded.",
                tone = OutcomeTone.SUCCESS,
                footnote = if (metrics.blankShare > 0.2f) {
                    "${(metrics.blankShare * 100).toInt()}% of frames came back black, which is " +
                        "what an app that blocks screen capture looks like from here."
                } else {
                    null
                },
            )

            else -> OutcomeCard(
                headline = "Not watching",
                detail = state.message
                    ?: "Video blurring needs screen capture permission, and Android asks for it " +
                    "again every session rather than remembering it.",
                tone = OutcomeTone.WARNING,
                footnote = "Start a session below. If it ends while the child is in another app, " +
                    "a notification offers it back rather than leaving it off quietly.",
            )
        }

        ProtectionSection(title = "Settings", icon = Icons.Rounded.Tune) {
            SettingSwitchRow(
                title = "Cover unsafe video",
                description = "Off means frames are still judged and recorded, but the screen is " +
                    "not covered.",
                checked = state.enabled,
                onCheckedChange = { viewModel.onEvent(VideoBlurringUiEvent.ToggleBlur(it)) },
            )
            Spacer(Modifier.height(14.dp))
            BlurStrengthSlider(
                strength = state.blurStrength,
                onChange = { viewModel.onEvent(VideoBlurringUiEvent.SetStrength(it)) },
                // The cover over another app cannot blur what it did not draw, so it hides by
                // weight instead. Every setting on this slider hides the picture; what moves is
                // how much of the app underneath the child can still make out around it.
                footnote = "Every setting covers the picture. Higher leaves less of the app " +
                    "underneath showing through.",
            )

            Spacer(Modifier.height(18.dp))
            Text(
                text = "What gets covered",
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
            )
            Spacer(Modifier.height(8.dp))
            BlurMode.entries.forEach { mode ->
                FilterPill(
                    text = mode.label,
                    selected = state.blurMode == mode,
                    onClick = { viewModel.onEvent(VideoBlurringUiEvent.SetBlurMode(mode)) },
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = state.blurMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = SafetyPalette.Slate,
            )

            if (state.blurMode != BlurMode.SUBJECTS) {
                // The control that decides whether anything gets covered at all, and until now it
                // existed only in storage: the classifier read it, enforcement obeyed it, and no
                // screen could change it. A parent watching borderline pictures go uncovered had
                // nothing to turn up. A setting with no way to reach it is the same as not having
                // the setting, except that the code reads as though the choice were offered.
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "What counts as unsafe",
                    style = MaterialTheme.typography.titleSmall,
                    color = SafetyPalette.Ink,
                )
                Spacer(Modifier.height(8.dp))
                ValuesCategory.entries.forEach { category ->
                    FilterPill(
                        text = category.label,
                        selected = category in state.blurCategories,
                        onClick = {
                            viewModel.onEvent(VideoBlurringUiEvent.ToggleCategory(category))
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    // Per category, not one blanket claim. Their reliability genuinely differs,
                    // and the one the product most wants is the one the model is worst at.
                    text = state.blurCategories
                        .joinToString(" ") { "${it.label}: ${it.detectionQuality.label}." }
                        .ifEmpty { "Nothing is selected, so nothing will be covered." },
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Slate,
                )

                state.blurCategories
                    .firstOrNull { it.detectionQuality == DetectionQuality.PROXY_ONLY }
                    ?.let { weakest ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = weakest.detectionQuality.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = SafetyPalette.Slate,
                        )
                    }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "How sure before covering",
                    style = MaterialTheme.typography.titleSmall,
                    color = SafetyPalette.Ink,
                )
                Spacer(Modifier.height(8.dp))
                ProtectionLevel.entries.forEach { level ->
                    FilterPill(
                        text = level.label,
                        selected = state.protectionLevel == level,
                        onClick = {
                            viewModel.onEvent(VideoBlurringUiEvent.SetProtectionLevel(level))
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = state.protectionLevel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Slate,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // The honest framing. The bundled model has no label for explicit content and
                    // infers it from swimwear and setting, so the dial is moving an inference,
                    // not a determination. Rule 11.
                    text = "The bundled model was not trained to judge safety, so this moves how " +
                        "readily an inference is acted on rather than how accurate it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Slate,
                )
            }

            if (state.blurMode == BlurMode.SUBJECTS) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Who to cover",
                    style = MaterialTheme.typography.titleSmall,
                    color = SafetyPalette.Ink,
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    SubjectCategory.PERSON,
                    SubjectCategory.APPEARS_MALE,
                    SubjectCategory.APPEARS_FEMALE,
                ).forEach { subject ->
                    FilterPill(
                        text = subject.label,
                        selected = subject in state.blurSubjects,
                        onClick = { viewModel.onEvent(VideoBlurringUiEvent.ToggleSubject(subject)) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // The reliability caveat sits next to the control rather than in a help page,
                // because a parent choosing an appearance category is exactly the person who
                // needs to know it is an estimate.
                Text(
                    text = state.blurSubjects
                        .firstOrNull { it.isAppearanceBased }
                        ?.description
                        ?: SubjectCategory.PERSON.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Slate,
                )
            }
        }

        ProtectionSection(title = "Session", icon = Icons.Rounded.Videocam) {
            if (state.capturing) {
                DetailRow("Frames judged", metrics.framesAnalysed.toString())
                DetailRow("Screen covered", metrics.blurEvents.toString())
                DetailRow("Frames skipped while busy", metrics.framesSkipped.toString())
                DetailRow(
                    "Frames that came back black",
                    "${metrics.blankFrames} (${(metrics.blankShare * 100).toInt()}%)",
                )
                DetailRow("Capture size", "${metrics.captureWidth}×${metrics.captureHeight}")
                DetailRow("Rate achieved", "%.1f per second".format(metrics.effectiveFps))

                // The product metric, kept distinct from the engineering ones above. How long
                // unsafe content was actually visible is the only figure that answers a parent's
                // real question, and quoting per-frame classification time instead is the
                // reporting mistake this feature invites.
                if (metrics.latency.coversRaised > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Time on screen before covering",
                        style = MaterialTheme.typography.titleSmall,
                        color = SafetyPalette.Ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    DetailRow("Average", "${metrics.latency.averageLatencyMillis} ms")
                    DetailRow("Worst measured", "${metrics.latency.worstLatencyMillis} ms")
                    DetailRow(
                        "Worst case with sampling gap",
                        "${metrics.latency.worstCaseExposureMillis} ms",
                    )
                    DetailRow(
                        "Of which classification",
                        "${metrics.latency.averageClassifyMillis} ms " +
                            "(${(metrics.latency.classifyShare * 100).toInt()}%)",
                    )
                    if (metrics.latency.worstCaseExposureMillis >
                        BlurLatency.ACCEPTABLE_WORST_CASE_MILLIS
                    ) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Above the one second target. Past roughly a second the cover " +
                                "reads as content being taken away rather than blocked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SafetyPalette.Danger,
                        )
                    }
                }

                if (metrics.framesScanned > 0) {
                    Spacer(Modifier.height(10.dp))
                    DetailRow(
                        "Images judged per screen",
                        "%.1f".format(metrics.regionsPerScan),
                    )
                    DetailRow("Images judged", metrics.regionsExamined.toString())
                    if (metrics.wholeFrameScans > 0) {
                        DetailRow(
                            "Judged whole screen instead",
                            "${metrics.wholeFrameScans} of ${metrics.framesScanned}",
                        )
                    }
                }

                if (metrics.analysisFailures > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // A session failing on every frame looks exactly like a session watching
                        // a safe screen. Saying so is the whole point of having the counter.
                        text = "${metrics.analysisFailures} frames were dropped because " +
                            "something failed while judging them. Those frames were not judged " +
                            "and nothing can be concluded from them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SafetyPalette.Danger,
                    )
                }

                if (state.blurMode == BlurMode.SUBJECTS && metrics.subjectCovers +
                    metrics.subjectFallbacks > 0
                ) {
                    Spacer(Modifier.height(10.dp))
                    DetailRow("Subjects covered", metrics.subjectsCovered.toString())
                    DetailRow(
                        "Fell back to covering the image",
                        "${metrics.subjectFallbacks} " +
                            "(${(metrics.subjectFallbackShare * 100).toInt()}%)",
                    )
                    if (metrics.subjectFallbackShare > 0.4f) {
                        Text(
                            text = "Most failing images had no detectable person in them, so this " +
                                "mode is mainly behaving like covering the image.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SafetyPalette.Slate,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                SecondaryButton(
                    text = "Stop watching",
                    onClick = { viewModel.onEvent(VideoBlurringUiEvent.StopCapture) },
                    icon = Icons.Rounded.Stop,
                    contentColor = SafetyPalette.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "Start a session, then open YouTube, Instagram or TikTok. The screen " +
                        "is covered while the video playing is judged unsafe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = "Start watching video",
                    onClick = { consentLauncher.launch(viewModel.captureConsentIntent()) },
                    icon = Icons.Rounded.Videocam,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ProtectionSection(title = "What to expect", icon = Icons.Rounded.Insights) {
            BulletLine(
                "Android asks for screen capture permission again every time a session starts. " +
                    "No app can hold it permanently, so this protection cannot run unattended.",
            )
            BulletLine(
                "This is the most expensive protection in the app. It samples a couple of frames " +
                    "a second at reduced resolution, and the cost per frame is shown above.",
            )
            BulletLine(
                "An app that marks its window secure, such as a banking app or a streaming " +
                    "service, captures as pure black. Those frames are counted separately rather " +
                    "than reported as safe.",
            )
            BulletLine(
                "Each picture on screen is judged on its own, so one image can be covered while " +
                    "the rest of the page stays readable.",
            )
            BulletLine(
                "A cover comes down when the screen moves on. While it is up, the capture sees " +
                    "the cover rather than what is under it, so it is also re-examined on a timer.",
            )
            BulletLine(
                "A covered picture lets taps and scrolls through to the app underneath, so the " +
                    "page still works normally. Android only allows that at full strength for an " +
                    "accessibility service, so without that permission a cover is limited to 80% " +
                    "and some of the picture shows through it.",
            )
            BulletLine(
                "While a page scrolls the covers move with it rather than being taken down and " +
                    "worked out again, so they stay on their pictures. They still follow about a " +
                    "tenth of a second behind, and a fast fling can move a page further than " +
                    "anything on it can be recognised from one frame to the next — then the " +
                    "covers come down and go back up on the new screen.",
            )
        }
    }
}

// --- shared ------------------------------------------------------------------------------------------

@Composable
private fun BlurStrengthSlider(
    strength: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    footnote: String = "Higher removes more detail. Below about half, the shape of what is in " +
        "the picture is still readable.",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Blur strength",
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(strength * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = SafetyPalette.Indigo,
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = strength,
            onValueChange = onChange,
            valueRange = 0.4f..1f,
            colors = SliderDefaults.colors(
                thumbColor = SafetyPalette.Indigo,
                activeTrackColor = SafetyPalette.Indigo,
                inactiveTrackColor = SafetyPalette.Line,
            ),
        )
        Text(
            text = footnote,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
    }
}
