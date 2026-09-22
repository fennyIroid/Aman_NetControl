package com.example.childsafetyimpl.platform.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.SeedDomainRules
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.platform.content.ImageBlur
import com.example.childsafetyimpl.platform.content.ImageSafetyClassifier
import com.example.childsafetyimpl.platform.network.AdBlockList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rule engine behind the built-in browser.
 *
 * Owning the rendering surface is the strongest content-safety position this app has, and this
 * class is where that ownership is cashed in. Every navigation and every subresource passes
 * through here before the web view is allowed to load it, so a rule is enforced rather than
 * requested.
 *
 * Three protections live here:
 *
 *  - **Site rules.** A blocked destination is refused at the navigation step, and a permitted page
 *    cannot pull content from a blocked one either.
 *  - **Image blurring.** An image request is fetched here, decoded, scored on this device, and
 *    either handed to the page unchanged or handed over blurred. The child never receives the
 *    pixels of an image that failed, which is a stronger promise than blurring something already
 *    on screen.
 *  - **Ad removal.** Ad and tracker requests are refused, and the slots they would have filled are
 *    hidden so the page does not end up full of reserved empty rectangles.
 *
 * These callbacks run on the web view's own background threads, so the calls into the rule cache
 * and the classifier have to be fast and must never touch the main thread.
 */
@Singleton
class SafeBrowserEngine @Inject constructor(
    private val rulesRepository: SafetyRulesRepository,
    private val classifier: ImageSafetyClassifier,
    private val imageBlur: ImageBlur,
    private val eventRepository: SafetyEventRepository,
    private val preferences: SafetyPreferences,
) {

    data class BlockedPage(val host: String, val reason: String, val category: String?)

    /**
     * Settings cached rather than read per request.
     *
     * A busy page makes a hundred subresource requests, and a DataStore read deserialises the
     * whole preference file each time. Reading it inside the intercept callback turned one page
     * load into a hundred disk reads, which is exactly the kind of cost that gets a filtering
     * browser called slow and switched off.
     */
    @Volatile private var cached: CachedSettings = CachedSettings()

    private data class CachedSettings(
        val adBlockEnabled: Boolean = true,
        val imageBlurEnabled: Boolean = true,
        val blurStrength: Float = 0.85f,
        val safeSearchEnabled: Boolean = true,
    )

    /** Called once by the app shell, and again whenever settings change. */
    suspend fun observeSettings() {
        preferences.settings.collect { settings ->
            cached = CachedSettings(
                adBlockEnabled = settings.adBlockEnabled,
                imageBlurEnabled = settings.imageBlurEnabled,
                blurStrength = settings.blurStrength,
                safeSearchEnabled = settings.safeSearchEnabled,
            )
        }
    }

    private val _lastBlock = MutableStateFlow<BlockedPage?>(null)
    val lastBlock: StateFlow<BlockedPage?> = _lastBlock.asStateFlow()

    private val _stats = MutableStateFlow(BrowsingStats())
    val stats: StateFlow<BrowsingStats> = _stats.asStateFlow()

    fun clearBlock() { _lastBlock.value = null }

    fun resetStats() { _stats.value = BrowsingStats() }

    /**
     * Decides whether a top-level navigation may proceed.
     *
     * Runs on the main thread, so the rule lookup uses the cached snapshot rather than the
     * database. Returning a rewritten URL rather than a boolean lets SafeSearch enforcement share
     * this one decision point.
     */
    fun evaluateNavigation(url: String, safeSearchEnabled: Boolean): NavigationDecision {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return NavigationDecision.Allow(url)
        val host = uri.host?.removePrefix("www.").orEmpty()
        if (host.isBlank()) return NavigationDecision.Allow(url)

        val verdict = runBlocking { rulesRepository.evaluateHost(host) }
        _stats.update { it.copy(navigations = it.navigations + 1) }

        if (!verdict.allowed) {
            _stats.update { it.copy(blockedNavigations = it.blockedNavigations + 1) }
            _lastBlock.value = BlockedPage(host, verdict.reason, verdict.category?.label)
            runBlocking {
                eventRepository.record(
                    type = SafetyEventType.RESTRICTED_WEBSITE,
                    title = "Blocked in the safe browser",
                    // Host and reason only. The path and query string are never recorded.
                    detail = "$host was blocked. ${verdict.reason}",
                    action = EventAction.BLOCKED,
                    capabilityId = "browser_domain_rules",
                    dedupeWindowMillis = 30_000L,
                )
            }
            return NavigationDecision.Block(host, verdict.reason)
        }

        if (safeSearchEnabled) {
            val rewritten = applySafeSearch(uri)
            if (rewritten != null) {
                _stats.update { it.copy(safeSearchRewrites = it.safeSearchRewrites + 1) }
                return NavigationDecision.Redirect(rewritten)
            }
        }

        return NavigationDecision.Allow(url)
    }

    sealed interface NavigationDecision {
        data class Allow(val url: String) : NavigationDecision
        data class Redirect(val url: String) : NavigationDecision
        data class Block(val host: String, val reason: String) : NavigationDecision
    }

    /**
     * Rewrites a search URL to carry the engine's strict-filtering parameter.
     *
     * Returns null when the URL is already correct, so an unnecessary redirect never loops.
     */
    private fun applySafeSearch(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null
        val (parameter, value) = SeedDomainRules.safeSearchParameters[host]
            ?: SeedDomainRules.safeSearchParameters[host.removePrefix("www.")]
            ?: return null

        if (uri.getQueryParameter(parameter) == value) return null
        if (uri.getQueryParameter("q").isNullOrBlank()) return null

        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames
            .filter { it != parameter }
            .forEach { name -> builder.appendQueryParameter(name, uri.getQueryParameter(name)) }
        builder.appendQueryParameter(parameter, value)
        return builder.build().toString()
    }

    /**
     * Intercepts a subresource request.
     *
     * Two jobs. Blocked hosts are refused outright, which stops a permitted page pulling content
     * from a blocked one. Image requests are fetched, scored and either passed through or replaced.
     *
     * Runs on a web view background thread. Returning null hands the request back to the web view
     * to load normally, which is the correct behaviour for anything this engine does not handle.
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        val host = url.host?.removePrefix("www.") ?: return null

        val settings = cached

        // Ads first. It is a set and substring check, it accounts for most requests on a typical
        // page, and it costs nothing compared to the rule lookup below.
        if (settings.adBlockEnabled && AdBlockList.blocksRequest(url.toString())) {
            _stats.update { it.copy(adsBlocked = it.adsBlocked + 1) }
            return emptyResponse()
        }

        val verdict = runBlocking { rulesRepository.evaluateHost(host) }
        if (!verdict.allowed) {
            // A programmatic load, such as the address bar, never reaches
            // shouldOverrideUrlLoading: Android only calls that for navigations the page or the
            // user starts inside it. So the main document has to be handled here as well, or a
            // typed address is refused with nothing on screen to say why.
            if (request.isForMainFrame) {
                _stats.update { it.copy(blockedNavigations = it.blockedNavigations + 1) }
                _lastBlock.value = BlockedPage(host, verdict.reason, verdict.category?.label)
                runBlocking {
                    eventRepository.record(
                        type = SafetyEventType.RESTRICTED_WEBSITE,
                        title = "Blocked in the safe browser",
                        detail = "$host was blocked. ${verdict.reason}",
                        action = EventAction.BLOCKED,
                        capabilityId = "browser_domain_rules",
                        dedupeWindowMillis = 30_000L,
                    )
                }
            } else {
                _stats.update { it.copy(blockedResources = it.blockedResources + 1) }
            }
            return refusedResponse()
        }

        if (!settings.imageBlurEnabled) return null
        if (!looksLikeImage(url.toString())) return null

        return interceptImage(url.toString(), host, settings.blurStrength)
    }

    private fun interceptImage(
        url: String,
        host: String,
        blurStrength: Float,
    ): WebResourceResponse? {
        val bytes = fetch(url) ?: return null
        if (bytes.size > MAX_IMAGE_BYTES) {
            // Too large to score inside a page load without a visible stall. Passed through, and
            // counted, because an unscored image is a real gap in the protection.
            _stats.update { it.copy(imagesSkipped = it.imagesSkipped + 1) }
            return null
        }

        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return null

        if (bitmap.width < MIN_IMAGE_DIMENSION || bitmap.height < MIN_IMAGE_DIMENSION) {
            // Icons, spacers and tracking pixels. Scoring them wastes time and produces noise.
            bitmap.recycle()
            _stats.update { it.copy(imagesSkipped = it.imagesSkipped + 1) }
            return null
        }

        // Already on a WebView background thread, and the interception contract is synchronous:
        // the response has to be returned, not promised. Blocking here is correct; blocking on
        // the main thread would not be.
        val result = runBlocking { classifier.classify(bitmap) }

        _stats.update {
            it.copy(
                imagesScored = it.imagesScored + 1,
                totalScoringMillis = it.totalScoringMillis + result.durationMillis,
                imagesCovered = it.imagesCovered + if (result.isUnsafe) 1 else 0,
            )
        }

        if (!result.isUnsafe) {
            bitmap.recycle()
            return WebResourceResponse(
                guessMimeType(url),
                null,
                ByteArrayInputStream(bytes),
            )
        }

        // The failing image is blurred here and the original bytes are dropped. Nothing that
        // could be un-blurred reaches the page.
        val blurred = runCatching { imageBlur.blurToJpeg(bitmap, blurStrength) }.getOrNull()
        bitmap.recycle()

        runBlocking {
            eventRepository.record(
                type = SafetyEventType.UNSAFE_IMAGE,
                title = "An image was blurred",
                detail = "On $host. %s".format(result.explanation),
                action = EventAction.BLURRED,
                capabilityId = "image_blurring",
                dedupeWindowMillis = 30_000L,
            )
        }

        return if (blurred != null) {
            WebResourceResponse("image/jpeg", null, ByteArrayInputStream(blurred))
        } else {
            WebResourceResponse(
                "image/png",
                null,
                ByteArrayInputStream(imageBlur.coveredPlaceholder()),
            )
        }
    }

    private fun fetch(url: String): ByteArray? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MILLIS
            readTimeout = FETCH_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ChildSafetyPrototype")
        }
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var read = input.read(buffer)
            var total = 0
            while (read != -1 && total <= MAX_IMAGE_BYTES) {
                output.write(buffer, 0, read)
                total += read
                read = input.read(buffer)
            }
            output.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * An empty document, not a message.
     *
     * Anything returned here is rendered as the page, and a bare line of text showing through
     * behind the block screen looks like a broken site rather than a deliberate refusal. The
     * explanation belongs to the block screen, which reads [lastBlock].
     */
    private fun refusedResponse(): WebResourceResponse = WebResourceResponse(
        "text/html",
        "utf-8",
        ByteArrayInputStream(
            "<!doctype html><html><body style=\"background:#F4F6FC\"></body></html>"
                .toByteArray(),
        ),
    )

    /**
     * An empty 200 rather than an error, for a refused ad.
     *
     * A failed request leaves some pages retrying in a loop or drawing their own "could not load"
     * furniture, and both are more visible than the ad would have been.
     */
    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun looksLikeImage(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun guessMimeType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    data class BrowsingStats(
        val navigations: Int = 0,
        val blockedNavigations: Int = 0,
        val blockedResources: Int = 0,
        val safeSearchRewrites: Int = 0,
        val imagesScored: Int = 0,
        val imagesCovered: Int = 0,
        val adsBlocked: Int = 0,
        val imagesSkipped: Int = 0,
        val totalScoringMillis: Long = 0,
    ) {
        val averageScoringMillis: Long
            get() = if (imagesScored == 0) 0 else totalScoringMillis / imagesScored
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        const val MIN_IMAGE_DIMENSION = 96
        const val FETCH_TIMEOUT_MILLIS = 6000
    }
}
