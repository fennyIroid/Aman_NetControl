package com.example.childsafetyimpl.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.platform.content.BlurMode
import com.example.childsafetyimpl.platform.content.ProtectionLevel
import com.example.childsafetyimpl.platform.content.SubjectCategory
import com.example.childsafetyimpl.platform.content.ValuesCategory
import com.example.childsafetyimpl.platform.network.FilteringMode
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.safetyDataStore: DataStore<Preferences> by preferencesDataStore(name = "child_safety_prefs")

/**
 * The child's profile, the protections that are switched on, and how strictly each one behaves.
 *
 * Protection switches are held as a set of feature ids rather than as one boolean per feature, so
 * adding a protection never means migrating stored preferences, and the enforcement engine can ask
 * one question ("is this feature on?") instead of matching on a name.
 */
data class SafetySettings(
    val onboardingComplete: Boolean = false,
    val childName: String = "",
    val childAge: Int = 10,
    val avatarIndex: Int = 0,
    val deviceLabel: String = "",

    /** Master switch. With this off, nothing is enforced and the UI says so plainly. */
    val protectionEnabled: Boolean = false,
    /** Ids from [ProtectionFeature]. */
    val enabledFeatures: Set<String> = defaultEnabledFeatures,

    // --- how strict each protection is -------------------------------------------------------

    /** Null refuses the surface outright; a number allows that many minutes a day. */
    val shortFormAllowanceMinutes: Int? = null,
    val statusAllowanceMinutes: Int? = null,
    val aiDailyBudgetMinutes: Int? = 30,
    val gameDailyBudgetMinutes: Int? = 60,

    /** Reads the AI conversation surface for risk indicators. Off means time tracking only. */
    val aiConversationScanEnabled: Boolean = true,

    /** Closes a VPN app the moment it is opened. */
    val blockVpnApps: Boolean = true,
    /** Takes the device tunnel back when another VPN claims it. */
    val reclaimTunnel: Boolean = true,
    /** Closes the system VPN settings screen. */
    val blockVpnSettings: Boolean = true,

    /** Closes cloning tools and cloned instances on open. */
    val blockCloneTools: Boolean = true,

    val adBlockEnabled: Boolean = true,
    val imageBlurEnabled: Boolean = true,
    val videoBlurEnabled: Boolean = true,
    /**
     * Whether a parent has started screen capture and not stopped it.
     *
     * Not the same as [videoBlurEnabled], and the difference is the point. That says the parent
     * wants video blurring; this says capture was actually running. Only the two together mean a
     * session that has gone away should be reported as a gap: a parent who has switched the
     * protection on but never granted capture is being asked for something, not told something
     * broke.
     *
     * It is persisted because the thing most likely to end a session — the process being killed —
     * takes every in-memory record of it with it. See [MonitoringService]'s capture check.
     */
    val captureRequested: Boolean = false,
    /** 0..1, how heavily a failing image or frame is blurred. */
    val blurStrength: Float = 0.85f,

    /**
     * How strict the blur verdict has to be before anything is covered.
     *
     * Exposed to a parent as three words rather than a threshold, because asking someone to
     * calibrate a classifier whose output they cannot see is not a setting, it is a trap.
     */
    val blurProtectionLevel: ProtectionLevel = ProtectionLevel.default,

    /**
     * Which content categories the blur acts on.
     *
     * Only explicit imagery is on by default. The brief is explicit that categories must not be
     * invented ahead of the POC outcome, so the others ship switched off and visible rather than
     * absent, which is what lets the client turn one on without a release.
     */
    val blurCategories: Set<ValuesCategory> = ValuesCategory.defaults,

    /** Whole-screen cover, or only the people in the picture. */
    val blurMode: BlurMode = BlurMode.default,

    /**
     * Which subjects the region cover targets.
     *
     * Defaults to everyone, because that is the setting that needs no appearance model and is
     * therefore the only one whose behaviour can be promised.
     */
    val blurSubjects: Set<SubjectCategory> = setOf(SubjectCategory.PERSON),

    /**
     * Forces SafeSearch for every app by answering search-engine lookups with the filtered
     * address each engine publishes, not only by rewriting URLs inside this app's own browser.
     */
    val safeSearchEnabled: Boolean = true,
    /** Reads the address bar and search box of other browsers, and leaves a restricted search. */
    val searchTermGuardEnabled: Boolean = true,
    /** The strictest browsing posture: nothing loads unless a parent allowed it by name. */
    val allowListOnlyBrowsing: Boolean = false,
    val notificationSignalsEnabled: Boolean = true,

    /**
     * Whether a parent has the internet paused.
     *
     * Deliberately persisted rather than held in the tunnel. A pause that a reboot lifts is worse
     * than no pause at all, because the parent believes it is still in force.
     */
    val internetPaused: Boolean = false,

    /**
     * How device-wide web filtering is delivered: the tunnel, Android's Private DNS, or Private DNS
     * with the tunnel raised only while a feature needs it.
     *
     * Defaults to the tunnel, which is how every install before this setting existed behaved, so
     * nobody's filtering changes underneath them on update. See [FilteringMode].
     */
    val filteringMode: FilteringMode = FilteringMode.VPN_TUNNEL,
    /** The Private DNS hostname the parent was told to enter. Only read in the DNS modes. */
    val privateDnsHostname: String = FilteringMode.DEFAULT_PRIVATE_DNS_HOST,

    val dailyScreenTimeLimitMinutes: Int = 240,
    val bedtimeStartMinuteOfDay: Int = 21 * 60,
    val bedtimeEndMinuteOfDay: Int = 7 * 60,

    /**
     * Last observed grant state, stored so a revocation can be detected on the next check. This
     * is the mechanism behind bypass detection.
     */
    val lastKnownGrants: Set<String> = emptySet(),
    val lastMonitorHeartbeat: Long = 0L,
) {

    fun isEnabled(feature: ProtectionFeature): Boolean =
        protectionEnabled && feature.id in enabledFeatures

    /** The allowance for a surface, or null when the surface is refused outright. */
    fun allowanceFor(surface: RestrictedSurface): Int? = when (surface) {
        RestrictedSurface.SHORT_FORM_FEED -> shortFormAllowanceMinutes
        RestrictedSurface.STATUS_STORY -> statusAllowanceMinutes
        else -> null
    }

    companion object {
        /**
         * Everything on by default. A parental control that ships with its protections off is a
         * settings screen pretending to be a safety product.
         */
        val defaultEnabledFeatures: Set<String> =
            ProtectionFeature.entries.map { it.id }.toSet()
    }
}

/**
 * DataStore-backed settings store: one class owns the keys rather than scattering them across
 * features.
 *
 * Open, and with [settings] overridable, so the rule and enforcement engines can be unit tested
 * against a fixed configuration without standing up a real DataStore.
 */
open class SafetyPreferences(private val context: Context) {

    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val childName = stringPreferencesKey("child_name")
        val childAge = intPreferencesKey("child_age")
        val avatarIndex = intPreferencesKey("avatar_index")
        val deviceLabel = stringPreferencesKey("device_label")

        val protectionEnabled = booleanPreferencesKey("protection_enabled")
        val enabledFeatures = stringSetPreferencesKey("enabled_features")

        /**
         * Which protections existed the last time the parent chose between them.
         *
         * Without this, a protection added in an update arrives switched off for every parent who
         * has ever touched a toggle, because their stored set was written before the new one
         * existed and does not contain it. That is the worst possible failure for a safety
         * product: the update ships a protection and nobody is protected by it.
         */
        val knownFeatures = stringSetPreferencesKey("known_features")

        val shortFormAllowance = intPreferencesKey("short_form_allowance")
        val statusAllowance = intPreferencesKey("status_allowance")
        val aiBudget = intPreferencesKey("ai_budget")
        val gameBudget = intPreferencesKey("game_budget")
        val aiConversationScan = booleanPreferencesKey("ai_conversation_scan")

        val blockVpnApps = booleanPreferencesKey("block_vpn_apps")
        val reclaimTunnel = booleanPreferencesKey("reclaim_tunnel")
        val blockVpnSettings = booleanPreferencesKey("block_vpn_settings")
        val blockCloneTools = booleanPreferencesKey("block_clone_tools")

        val adBlock = booleanPreferencesKey("ad_block")
        val imageBlur = booleanPreferencesKey("image_blur")
        val videoBlur = booleanPreferencesKey("video_blur")
        val blurStrength = intPreferencesKey("blur_strength")
        val blurLevel = stringPreferencesKey("blur_protection_level")
        val blurCategories = stringSetPreferencesKey("blur_categories")
        val blurMode = stringPreferencesKey("blur_mode")
        val blurSubjects = stringSetPreferencesKey("blur_subjects")

        val safeSearch = booleanPreferencesKey("safe_search")
        val searchTermGuard = booleanPreferencesKey("search_term_guard")
        val allowListOnly = booleanPreferencesKey("allow_list_only")
        val notificationSignals = booleanPreferencesKey("notification_signals")

        val internetPaused = booleanPreferencesKey("internet_paused")
        val filteringMode = stringPreferencesKey("filtering_mode")
        val privateDnsHostname = stringPreferencesKey("private_dns_hostname")

        val dailyLimit = intPreferencesKey("daily_screen_limit")
        val bedtimeStart = intPreferencesKey("bedtime_start")
        val bedtimeEnd = intPreferencesKey("bedtime_end")

        val captureRequested = booleanPreferencesKey("capture_requested")

        val lastKnownGrants = stringSetPreferencesKey("last_known_grants")
        val lastHeartbeat = longPreferencesKey("last_monitor_heartbeat")
    }

    /**
     * A sentinel for "refuse outright", because DataStore cannot store a null Int and the
     * difference between "no allowance set" and "an allowance of zero" matters here.
     */
    private val refuseOutright = -1

    // A getter rather than an initialised property, so a subclass that overrides it never
    // triggers DataStore creation in the base constructor.
    open val settings: Flow<SafetySettings>
        get() = context.safetyDataStore.data.map { prefs ->
            SafetySettings(
                onboardingComplete = prefs[Keys.onboardingComplete] ?: false,
                childName = prefs[Keys.childName] ?: "",
                childAge = prefs[Keys.childAge] ?: 10,
                avatarIndex = prefs[Keys.avatarIndex] ?: 0,
                deviceLabel = prefs[Keys.deviceLabel] ?: "",

                protectionEnabled = prefs[Keys.protectionEnabled] ?: false,
                enabledFeatures = prefs[Keys.enabledFeatures]?.let { stored ->
                    // Anything the parent has never been offered is new, and new protections
                    // start on. Anything they switched off stays off.
                    val known = prefs[Keys.knownFeatures] ?: stored
                    stored + (SafetySettings.defaultEnabledFeatures - known)
                } ?: SafetySettings.defaultEnabledFeatures,

                shortFormAllowanceMinutes = prefs[Keys.shortFormAllowance].toAllowance(),
                statusAllowanceMinutes = prefs[Keys.statusAllowance].toAllowance(),
                aiDailyBudgetMinutes = prefs[Keys.aiBudget]?.toAllowance() ?: 30,
                gameDailyBudgetMinutes = prefs[Keys.gameBudget]?.toAllowance() ?: 60,
                aiConversationScanEnabled = prefs[Keys.aiConversationScan] ?: true,

                blockVpnApps = prefs[Keys.blockVpnApps] ?: true,
                reclaimTunnel = prefs[Keys.reclaimTunnel] ?: true,
                blockVpnSettings = prefs[Keys.blockVpnSettings] ?: true,
                blockCloneTools = prefs[Keys.blockCloneTools] ?: true,

                adBlockEnabled = prefs[Keys.adBlock] ?: true,
                imageBlurEnabled = prefs[Keys.imageBlur] ?: true,
                videoBlurEnabled = prefs[Keys.videoBlur] ?: true,
                captureRequested = prefs[Keys.captureRequested] ?: false,
                blurStrength = (prefs[Keys.blurStrength] ?: 85) / 100f,

                safeSearchEnabled = prefs[Keys.safeSearch] ?: true,
                searchTermGuardEnabled = prefs[Keys.searchTermGuard] ?: true,
                allowListOnlyBrowsing = prefs[Keys.allowListOnly] ?: false,
                notificationSignalsEnabled = prefs[Keys.notificationSignals] ?: true,

                dailyScreenTimeLimitMinutes = prefs[Keys.dailyLimit] ?: 240,
                bedtimeStartMinuteOfDay = prefs[Keys.bedtimeStart] ?: (21 * 60),
                bedtimeEndMinuteOfDay = prefs[Keys.bedtimeEnd] ?: (7 * 60),

                internetPaused = prefs[Keys.internetPaused] ?: false,
                filteringMode = prefs[Keys.filteringMode]
                    ?.let { stored -> FilteringMode.entries.firstOrNull { it.name == stored } }
                    ?: FilteringMode.VPN_TUNNEL,
                privateDnsHostname = prefs[Keys.privateDnsHostname]
                    ?: FilteringMode.DEFAULT_PRIVATE_DNS_HOST,
                blurProtectionLevel = prefs[Keys.blurLevel]
                    ?.let { stored -> ProtectionLevel.entries.firstOrNull { it.name == stored } }
                    ?: ProtectionLevel.default,
                blurCategories = prefs[Keys.blurCategories]
                    ?.mapNotNull { stored -> ValuesCategory.entries.firstOrNull { it.name == stored } }
                    ?.toSet()
                    ?: ValuesCategory.defaults,
                blurMode = prefs[Keys.blurMode]
                    ?.let { stored -> BlurMode.entries.firstOrNull { it.name == stored } }
                    ?: BlurMode.default,
                blurSubjects = prefs[Keys.blurSubjects]
                    ?.mapNotNull { stored -> SubjectCategory.entries.firstOrNull { it.name == stored } }
                    ?.toSet()
                    ?: setOf(SubjectCategory.PERSON),
                lastKnownGrants = prefs[Keys.lastKnownGrants] ?: emptySet(),
                lastMonitorHeartbeat = prefs[Keys.lastHeartbeat] ?: 0L,
            )
        }

    private fun Int?.toAllowance(): Int? = when {
        this == null -> null
        this <= refuseOutright -> null
        else -> this
    }

    suspend fun completeOnboarding(name: String, age: Int, avatarIndex: Int, deviceLabel: String) {
        context.safetyDataStore.edit {
            it[Keys.onboardingComplete] = true
            it[Keys.childName] = name
            it[Keys.childAge] = age
            it[Keys.avatarIndex] = avatarIndex
            it[Keys.deviceLabel] = deviceLabel
        }
    }

    suspend fun setProtectionEnabled(enabled: Boolean) =
        putBoolean(Keys.protectionEnabled, enabled)

    suspend fun setFeatureEnabled(feature: ProtectionFeature, enabled: Boolean) {
        context.safetyDataStore.edit { prefs ->
            val known = prefs[Keys.knownFeatures] ?: prefs[Keys.enabledFeatures]
            val current = prefs[Keys.enabledFeatures]?.let { stored ->
                stored + (SafetySettings.defaultEnabledFeatures - (known ?: stored))
            } ?: SafetySettings.defaultEnabledFeatures

            prefs[Keys.enabledFeatures] =
                if (enabled) current + feature.id else current - feature.id
            // What the parent was choosing between, so the next update can tell what is new.
            prefs[Keys.knownFeatures] = SafetySettings.defaultEnabledFeatures
        }
    }

    /** Null means the surface is refused outright rather than allowanced. */
    suspend fun setSurfaceAllowance(surface: RestrictedSurface, minutes: Int?) {
        val key = when (surface) {
            RestrictedSurface.SHORT_FORM_FEED -> Keys.shortFormAllowance
            RestrictedSurface.STATUS_STORY -> Keys.statusAllowance
            else -> return
        }
        context.safetyDataStore.edit { it[key] = minutes ?: refuseOutright }
    }

    suspend fun setAiBudget(minutes: Int?) {
        context.safetyDataStore.edit { it[Keys.aiBudget] = minutes ?: refuseOutright }
    }

    suspend fun setGameBudget(minutes: Int?) {
        context.safetyDataStore.edit { it[Keys.gameBudget] = minutes ?: refuseOutright }
    }

    suspend fun setAiConversationScan(enabled: Boolean) =
        putBoolean(Keys.aiConversationScan, enabled)

    suspend fun setBlockVpnApps(enabled: Boolean) = putBoolean(Keys.blockVpnApps, enabled)
    suspend fun setReclaimTunnel(enabled: Boolean) = putBoolean(Keys.reclaimTunnel, enabled)
    suspend fun setBlockVpnSettings(enabled: Boolean) = putBoolean(Keys.blockVpnSettings, enabled)
    suspend fun setBlockCloneTools(enabled: Boolean) = putBoolean(Keys.blockCloneTools, enabled)

    suspend fun setAdBlock(enabled: Boolean) = putBoolean(Keys.adBlock, enabled)
    suspend fun setImageBlur(enabled: Boolean) = putBoolean(Keys.imageBlur, enabled)
    suspend fun setVideoBlur(enabled: Boolean) = putBoolean(Keys.videoBlur, enabled)

    /** Recorded when capture starts and when a parent stops it. See [SafetySettings.captureRequested]. */
    open suspend fun setCaptureRequested(requested: Boolean) =
        putBoolean(Keys.captureRequested, requested)

    suspend fun setBlurStrength(strength: Float) {
        context.safetyDataStore.edit {
            it[Keys.blurStrength] = (strength.coerceIn(0f, 1f) * 100).toInt()
        }
    }

    suspend fun setSafeSearch(enabled: Boolean) = putBoolean(Keys.safeSearch, enabled)
    suspend fun setSearchTermGuard(enabled: Boolean) = putBoolean(Keys.searchTermGuard, enabled)
    suspend fun setAllowListOnly(enabled: Boolean) = putBoolean(Keys.allowListOnly, enabled)
    suspend fun setNotificationSignals(enabled: Boolean) =
        putBoolean(Keys.notificationSignals, enabled)

    suspend fun setDailyLimit(minutes: Int) {
        context.safetyDataStore.edit { it[Keys.dailyLimit] = minutes }
    }

    suspend fun setBedtime(startMinuteOfDay: Int, endMinuteOfDay: Int) {
        context.safetyDataStore.edit {
            it[Keys.bedtimeStart] = startMinuteOfDay
            it[Keys.bedtimeEnd] = endMinuteOfDay
        }
    }

    open suspend fun setBlurProtectionLevel(level: ProtectionLevel) {
        context.safetyDataStore.edit { it[Keys.blurLevel] = level.name }
    }

    open suspend fun setBlurMode(mode: BlurMode) {
        context.safetyDataStore.edit { it[Keys.blurMode] = mode.name }
    }

    open suspend fun setBlurSubjects(subjects: Set<SubjectCategory>) {
        context.safetyDataStore.edit { it[Keys.blurSubjects] = subjects.map { s -> s.name }.toSet() }
    }

    open suspend fun setBlurCategories(categories: Set<ValuesCategory>) {
        context.safetyDataStore.edit { it[Keys.blurCategories] = categories.map { c -> c.name }.toSet() }
    }

    /** Written before the pause is applied, so a crash leaves the device paused, not online. */
    open suspend fun setInternetPaused(paused: Boolean) {
        context.safetyDataStore.edit { it[Keys.internetPaused] = paused }
    }

    open suspend fun setFilteringMode(mode: FilteringMode) {
        context.safetyDataStore.edit { it[Keys.filteringMode] = mode.name }
    }

    suspend fun setPrivateDnsHostname(hostname: String) {
        context.safetyDataStore.edit { it[Keys.privateDnsHostname] = hostname }
    }

    suspend fun recordGrants(grants: Set<String>) {
        context.safetyDataStore.edit { it[Keys.lastKnownGrants] = grants }
    }

    suspend fun recordHeartbeat(atMillis: Long) {
        context.safetyDataStore.edit { it[Keys.lastHeartbeat] = atMillis }
    }

    suspend fun reset() {
        context.safetyDataStore.edit { it.clear() }
    }

    private suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.safetyDataStore.edit { it[key] = value }
    }
}
