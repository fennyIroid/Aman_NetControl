package com.example.childsafetyimpl.ux.protection

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.browser.SafeBrowserEngine
import com.example.childsafetyimpl.platform.content.BlurMode
import com.example.childsafetyimpl.platform.content.PrivateTabAction
import com.example.childsafetyimpl.platform.content.ProtectionLevel
import com.example.childsafetyimpl.platform.content.ValuesCategory
import com.example.childsafetyimpl.platform.content.SubjectCategory
import com.example.childsafetyimpl.platform.content.SafetyLabelRisk
import com.example.childsafetyimpl.platform.network.AdBlockList
import com.example.childsafetyimpl.platform.projection.ScreenAnalysisService
import com.example.childsafetyimpl.platform.youtube.YouTubeSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- Ad removal ---------------------------------------------------------------------------------

data class AdRemovalUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val enabled: Boolean = true,
    val browserAdsBlocked: Int = 0,
    val playerAdsBlocked: Int = 0,
    val hostCount: Int = AdBlockList.hostCount,
    val patternCount: Int = AdBlockList.patternCount,
) {
    val totalBlocked: Int get() = browserAdsBlocked + playerAdsBlocked
}

sealed interface AdRemovalUiEvent {
    data object Back : AdRemovalUiEvent
    data class ToggleFeature(val enabled: Boolean) : AdRemovalUiEvent
    data class ToggleAdBlock(val enabled: Boolean) : AdRemovalUiEvent
    data object OpenPlayer : AdRemovalUiEvent
    data object OpenBrowser : AdRemovalUiEvent
}

class GetAdRemovalUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val browserEngine: SafeBrowserEngine,
    private val youTubeSurface: YouTubeSurface,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(AdRemovalUiState())
    val uiState: StateFlow<AdRemovalUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observe(ProtectionFeature.AD_REMOVAL),
            browserEngine.stats,
            combine(youTubeSurface.stats, preferences.settings) { player, settings ->
                player to settings
            },
        ) { status, browser, playerAndSettings ->
            AdRemovalUiState(
                loading = false,
                status = status,
                enabled = playerAndSettings.second.adBlockEnabled,
                browserAdsBlocked = browser.adsBlocked,
                playerAdsBlocked = playerAndSettings.first.adsBlocked,
            )
        }.collect { state -> _uiState.update { state } }
    }
}

@HiltViewModel
class AdRemovalViewModel @Inject constructor(
    private val useCase: GetAdRemovalUiStateUseCase,
    private val preferences: SafetyPreferences,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<AdRemovalUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
    }

    fun onEvent(event: AdRemovalUiEvent) {
        when (event) {
            AdRemovalUiEvent.Back -> navigate(NavigationAction.Back)

            is AdRemovalUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(ProtectionFeature.AD_REMOVAL, event.enabled)
            }

            is AdRemovalUiEvent.ToggleAdBlock -> viewModelScope.launch {
                preferences.setAdBlock(event.enabled)
            }

            AdRemovalUiEvent.OpenPlayer ->
                navigate(NavigationAction.To(Routes.youtubePlayer()))

            AdRemovalUiEvent.OpenBrowser ->
                navigate(NavigationAction.To(Routes.safeBrowser()))
        }
    }
}

// --- Image blurring ------------------------------------------------------------------------------

data class ImageBlurringUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val enabled: Boolean = true,
    val blurStrength: Float = 0.85f,
    val stats: SafeBrowserEngine.BrowsingStats = SafeBrowserEngine.BrowsingStats(),
    val mappedLabelCount: Int = SafetyLabelRisk.mappedLabelCount,
    val exposureLabelCount: Int = SafetyLabelRisk.exposureLabelCount,
)

sealed interface ImageBlurringUiEvent {
    data object Back : ImageBlurringUiEvent
    data class ToggleFeature(val enabled: Boolean) : ImageBlurringUiEvent
    data class ToggleBlur(val enabled: Boolean) : ImageBlurringUiEvent
    data class SetStrength(val strength: Float) : ImageBlurringUiEvent
    data object OpenBrowser : ImageBlurringUiEvent
}

class GetImageBlurringUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val browserEngine: SafeBrowserEngine,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(ImageBlurringUiState())
    val uiState: StateFlow<ImageBlurringUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observe(ProtectionFeature.IMAGE_BLURRING),
            browserEngine.stats,
            preferences.settings,
        ) { status, stats, settings ->
            ImageBlurringUiState(
                loading = false,
                status = status,
                enabled = settings.imageBlurEnabled,
                blurStrength = settings.blurStrength,
                stats = stats,
            )
        }.collect { state -> _uiState.update { state } }
    }
}

@HiltViewModel
class ImageBlurringViewModel @Inject constructor(
    private val useCase: GetImageBlurringUiStateUseCase,
    private val preferences: SafetyPreferences,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<ImageBlurringUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
    }

    fun onEvent(event: ImageBlurringUiEvent) {
        when (event) {
            ImageBlurringUiEvent.Back -> navigate(NavigationAction.Back)

            is ImageBlurringUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(ProtectionFeature.IMAGE_BLURRING, event.enabled)
            }

            is ImageBlurringUiEvent.ToggleBlur -> viewModelScope.launch {
                preferences.setImageBlur(event.enabled)
            }

            is ImageBlurringUiEvent.SetStrength -> viewModelScope.launch {
                preferences.setBlurStrength(event.strength)
            }

            ImageBlurringUiEvent.OpenBrowser ->
                navigate(NavigationAction.To(Routes.safeBrowser()))
        }
    }
}

// --- Video blurring ------------------------------------------------------------------------------

data class VideoBlurringUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val enabled: Boolean = true,
    val blurStrength: Float = 0.85f,
    val capturing: Boolean = false,
    val message: String? = null,
    val metrics: ScreenAnalysisService.State.CaptureMetrics =
        ScreenAnalysisService.State.CaptureMetrics(),
    val currentlyBlurring: Boolean = false,
    val blurMode: BlurMode = BlurMode.default,
    val blurSubjects: Set<SubjectCategory> = setOf(SubjectCategory.PERSON),
    val protectionLevel: ProtectionLevel = ProtectionLevel.default,
    val blurCategories: Set<ValuesCategory> = ValuesCategory.defaults,
    val privateTabAction: PrivateTabAction = PrivateTabAction.default,
)

sealed interface VideoBlurringUiEvent {
    data object Back : VideoBlurringUiEvent
    data class ToggleFeature(val enabled: Boolean) : VideoBlurringUiEvent
    data class ToggleBlur(val enabled: Boolean) : VideoBlurringUiEvent
    data class SetStrength(val strength: Float) : VideoBlurringUiEvent
    data class SetBlurMode(val mode: BlurMode) : VideoBlurringUiEvent
    data class SetPrivateTabAction(val action: PrivateTabAction) : VideoBlurringUiEvent
    data class SetProtectionLevel(val level: ProtectionLevel) : VideoBlurringUiEvent
    data class ToggleCategory(val category: ValuesCategory) : VideoBlurringUiEvent
    data class ToggleSubject(val subject: SubjectCategory) : VideoBlurringUiEvent
    data class CaptureGranted(val resultCode: Int, val data: Intent) : VideoBlurringUiEvent
    data object StopCapture : VideoBlurringUiEvent
    data object OpenPermissions : VideoBlurringUiEvent
}

class GetVideoBlurringUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val overlayController: com.example.childsafetyimpl.platform.enforcement.BlockOverlayController,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(VideoBlurringUiState())
    val uiState: StateFlow<VideoBlurringUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observe(ProtectionFeature.VIDEO_BLURRING),
            ScreenAnalysisService.State.running,
            ScreenAnalysisService.State.metrics,
            ScreenAnalysisService.State.message,
            combine(overlayController.blurring, preferences.settings) { blurring, settings ->
                blurring to settings
            },
        ) { status, running, metrics, message, blurringAndSettings ->
            VideoBlurringUiState(
                loading = false,
                status = status,
                enabled = blurringAndSettings.second.videoBlurEnabled,
                blurStrength = blurringAndSettings.second.blurStrength,
                capturing = running,
                message = message,
                metrics = metrics,
                currentlyBlurring = blurringAndSettings.first,
                blurMode = blurringAndSettings.second.blurMode,
                protectionLevel = blurringAndSettings.second.blurProtectionLevel,
                blurCategories = blurringAndSettings.second.blurCategories,
                blurSubjects = blurringAndSettings.second.blurSubjects,
                privateTabAction = blurringAndSettings.second.privateTabAction,
            )
        }.collect { state -> _uiState.update { state } }
    }
}

@HiltViewModel
class VideoBlurringViewModel @Inject constructor(
    private val useCase: GetVideoBlurringUiStateUseCase,
    private val preferences: SafetyPreferences,
    @param:ApplicationContext private val context: Context,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<VideoBlurringUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
    }

    /**
     * The consent intent the screen has to launch.
     *
     * Screen capture consent can only be asked for from an Activity, and Android 14 and later
     * require it again for every session. That is a real product constraint rather than an
     * oversight, and the screen says so instead of implying this runs unattended forever.
     */
    fun captureConsentIntent(): Intent {
        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    fun onEvent(event: VideoBlurringUiEvent) {
        when (event) {
            VideoBlurringUiEvent.Back -> navigate(NavigationAction.Back)

            is VideoBlurringUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(ProtectionFeature.VIDEO_BLURRING, event.enabled)
            }

            is VideoBlurringUiEvent.ToggleBlur -> viewModelScope.launch {
                preferences.setVideoBlur(event.enabled)
            }

            is VideoBlurringUiEvent.SetBlurMode -> viewModelScope.launch {
                preferences.setBlurMode(event.mode)
            }

            is VideoBlurringUiEvent.SetPrivateTabAction -> viewModelScope.launch {
                preferences.setPrivateTabAction(event.action)
            }

            is VideoBlurringUiEvent.SetProtectionLevel -> viewModelScope.launch {
                preferences.setBlurProtectionLevel(event.level)
            }

            is VideoBlurringUiEvent.ToggleCategory -> viewModelScope.launch {
                val current = uiState.value.blurCategories
                val next = if (event.category in current) current - event.category
                else current + event.category
                // Never allow an empty selection. With nothing selected the classifier runs, the
                // screen still reads as protecting, and nothing can ever be covered.
                preferences.setBlurCategories(
                    next.ifEmpty { setOf(ValuesCategory.EXPLICIT_IMAGERY) },
                )
            }

            is VideoBlurringUiEvent.ToggleSubject -> viewModelScope.launch {
                val current = uiState.value.blurSubjects
                val next = if (event.subject in current) current - event.subject else current + event.subject
                // Never allow an empty selection. Subject mode with nothing selected covers
                // nothing while the feature still reads as on, which is the failure this whole
                // area is built to avoid.
                preferences.setBlurSubjects(
                    next.ifEmpty { setOf(SubjectCategory.PERSON) },
                )
            }

            is VideoBlurringUiEvent.SetStrength -> viewModelScope.launch {
                preferences.setBlurStrength(event.strength)
            }

            is VideoBlurringUiEvent.CaptureGranted ->
                ScreenAnalysisService.start(context, event.resultCode, event.data)

            VideoBlurringUiEvent.StopCapture ->
                ScreenAnalysisService.stop(context)

            VideoBlurringUiEvent.OpenPermissions ->
                navigate(NavigationAction.To(Routes.permissions()))
        }
    }
}
