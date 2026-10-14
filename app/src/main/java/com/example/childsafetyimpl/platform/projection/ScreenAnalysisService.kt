package com.example.childsafetyimpl.platform.projection

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.childsafetyimpl.BuildConfig
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.alerts.SafetyNotifier
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.platform.content.BlurLatency
import com.example.childsafetyimpl.platform.content.BlurMode
import com.example.childsafetyimpl.platform.accessibility.BrowserSurfaces
import com.example.childsafetyimpl.platform.accessibility.SafetyAccessibilityService
import com.example.childsafetyimpl.platform.accessibility.ScreenSignalStore
import com.example.childsafetyimpl.platform.content.PrivateTabTracker
import com.example.childsafetyimpl.platform.content.SecureFrame
import com.example.childsafetyimpl.platform.content.CoverPolicy
import com.example.childsafetyimpl.platform.content.FrameMotion
import com.example.childsafetyimpl.platform.content.FrameScanner
import com.example.childsafetyimpl.platform.content.ImageBlur
import com.example.childsafetyimpl.platform.content.RowProfile
import com.example.childsafetyimpl.platform.content.SceneSignature
import com.example.childsafetyimpl.platform.content.ScreenRegions
import com.example.childsafetyimpl.platform.content.SubjectDetector
import com.example.childsafetyimpl.platform.enforcement.BlockOverlayController
import com.example.childsafetyimpl.ui.block.CoveredPatch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.get
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Video blurring.
 *
 * This is the only mechanism that reaches inside YouTube, Instagram or TikTok and does something
 * about what is actually playing. It captures frames a couple of times a second, classifies each
 * one on this device, and raises a frosted layer over the screen while the picture stays unsafe.
 * The layer lifts by itself once several clean frames in a row have arrived, so a single bad
 * frame in a safe video does not leave the screen covered.
 *
 * Two properties are worth stating because they are what make this acceptable to run at all.
 * Nothing captured is written anywhere: frames are classified in memory and recycled, and only a
 * score survives. And a window marked secure by its own app captures as pure black rather than as
 * an error, so the service counts those separately instead of reporting them as "nothing unsafe
 * here", which they are not.
 *
 * The costs are real and are reported rather than hidden: capture consent has to be granted again
 * every session from Android 14, and continuous capture is the most expensive protection in the
 * app.
 */
@AndroidEntryPoint
class ScreenAnalysisService : Service() {

    @Inject lateinit var projectionManager: MediaProjectionManager
    @Inject lateinit var frameScanner: FrameScanner
    @Inject lateinit var imageBlur: ImageBlur
    @Inject lateinit var subjectDetector: SubjectDetector
    @Inject lateinit var eventRepository: SafetyEventRepository
    @Inject lateinit var notifier: SafetyNotifier
    @Inject lateinit var overlayController: BlockOverlayController
    @Inject lateinit var preferences: SafetyPreferences
    @Inject lateinit var signalStore: ScreenSignalStore
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    /** Guards against queueing analysis faster than it completes. */
    @Volatile private var analysisInFlight = false
    @Volatile private var lastSampleAt = 0L

    /**
     * What is covered right now, and since when.
     *
     * Held here rather than derived from the overlay because the decision to keep a cover up is
     * made from evidence the overlay knows nothing about. [CoverPolicy] owns the rules.
     */
    @Volatile private var activeCovers: List<CoverPolicy.ActiveCover> = emptyList()

    /**
     * The last frame's fingerprint, which is how this loop knows whether anything happened.
     *
     * Classifying every region of every frame is affordable in milliseconds and not in battery,
     * and it is also pointless: the answer cannot change while the screen does not. So the
     * expensive pass runs when this says the screen moved, and on a timer besides.
     */
    @Volatile private var lastSignature: SceneSignature? = null

    /**
     * The last frame's vertical profile, which is how this loop knows *where* things went.
     *
     * Kept separately from [lastSignature] because it is refreshed on every frame this service
     * looks at, including the cheap ones between classifier passes, whereas the signature only
     * moves at the classification rate. Comparing a profile against one from half a second ago
     * would measure the scroll this loop has already followed.
     */
    @Volatile private var lastProfile: RowProfile? = null

    /**
     * The last frame's grid, which is how this loop knows it is looking at a different screen.
     *
     * A third remembered frame, for the third question, because the three are not the same
     * measurement and taking one of them twice is what produced every complaint about this
     * feature. [lastProfile] is where things went. [lastSignature] is whether it is worth looking
     * again. This is whether what is on screen is the same screen at all, taken on every frame
     * rather than at the classification rate: a cover is wrong the moment the page under it is
     * replaced, and half a second of it standing over the next page is half a second of a
     * rectangle over content nobody has looked at.
     */
    @Volatile private var lastLayout: SceneSignature? = null

    /**
     * The window-change count last seen from the accessibility service. Negative before the first.
     *
     * Only the count is kept. Which app it was is not this service's business and is not written
     * down here or anywhere else.
     */
    @Volatile private var lastWindowChanges = -1L

    @Volatile private var lastScanAt = 0L

    /** When the covers were last moved to follow the page, as opposed to re-derived. */
    @Volatile private var lastTrackAt = 0L

    /**
     * One buffer for the frame's pixels, reused for the life of the session.
     *
     * A capture frame is a third of a million integers, and the tracking pass reads them several
     * times a second. Allocating that per frame hands the collector tens of megabytes a second to
     * clean up, on the device, while a child is scrolling. Only one pass runs at a time —
     * [analysisInFlight] is what guarantees it — so a single buffer is safe.
     */
    private var pixelBuffer = IntArray(0)

    /**
     * The strength the covers on screen were drawn at.
     *
     * Remembered so that moving them does not have to read the parent's settings. A tracking pass
     * runs several times a second while a page is moving, and a preference read per pass is a disk
     * read per pass — rule 6, on the hottest path this app has.
     */
    @Volatile private var drawnStrength = 0f

    /**
     * Set when a cover was taken down, forcing the next frame to be examined.
     *
     * A cover is dropped so that what it was hiding can be looked at again, and the frame it is
     * dropped on still shows the cover. The look has to happen on the frame after, not at the next
     * scheduled scan, or the gap between the two is the content on display uncovered.
     */
    @Volatile private var forceScan = false

    /**
     * What each cover is drawing: a heavily reduced copy of the picture it is standing on.
     *
     * Kept here, alongside the covers, because it can only be taken while the picture is visible.
     * The moment a cover goes up, the capture shows the cover there, so re-sampling would blur the
     * blur and the patch would fade to a flat rectangle over a few seconds. A preview is therefore
     * taken when the evidence is seen and held for as long as the cover stands on it.
     */
    @Volatile private var patches: List<Patch> = emptyList()

    /**
     * Holds the preview already wrapped for Compose rather than the raw bitmap.
     *
     * Wrapping produces a new object every call, so re-wrapping per frame made every patch compare
     * unequal to the identical patch from the frame before, and the overlay re-drew itself twice a
     * second over a page the child was trying to scroll. Wrapped once, when the picture is sampled.
     */
    private data class Patch(
        val region: ScreenRegions.Region,
        val preview: androidx.compose.ui.graphics.ImageBitmap?,
    )

    /**
     * What is actually on screen, which is not always the same as what is covered.
     *
     * [activeCovers] is the decision and this is the drawing, and the whole-screen mode is where
     * they part company: one cover stands for every one of them. Everything that has to leave this
     * app's own output out of its measurements — the change fingerprint, the region scan — needs
     * what is drawn rather than what was decided, or it ends up asking whether this app's own
     * static paint has moved.
     */
    @Volatile private var drawnRegions: List<ScreenRegions.Region> = emptyList()

    /**
     * Where the covers were before they last changed, and until when that still matters.
     *
     * Asking for a cover and the capture showing it are not the same moment: the window is laid
     * out and composited after the request, so the next frame or two can still show the covers as
     * they were. Measured against [drawnRegions] alone, the ground a cover has just left is
     * compared as page while it still shows the cover, and the change of this app's own paint
     * reads as the page changing — which is a new layout, which drops every cover. So for a short
     * while after a change both sets are left out of the motion measurements. See [drawRegions].
     */
    @Volatile private var settlingRegions: List<ScreenRegions.Region> = emptyList()

    /**
     * The app-reported scroll total the covers on screen are placed for. See [ScrollLedger].
     *
     * Every cover position is true of one moment of the page, and this says which: moving the
     * covers to any other moment is a shift by the difference between that moment's total and
     * this one. Kept alongside the covers rather than recomputed so that a scroll is applied
     * exactly once, whichever pass — tracking, full, or the catch-up before drawing — gets to it.
     */
    @Volatile private var coversAnchoredScroll = 0L

    /** The display's height in pixels, which is what the app's scroll reports are measured in. */
    @Volatile private var screenHeightPx = 1

    /** Whether a browser's private tab is in front; see notePrivateTab. Guarded by itself. */
    private val privateTabs = PrivateTabTracker()

    /** Re-reads the last clear frame once the screen has been still; see confirmPrivateTabStill. */
    private var privateStillCheck: Job? = null

    /** When Android last confirmed the private tab's window is secure; see verifyPrivateTab. */
    @Volatile private var privateVerifiedAt = 0L

    /** A browser Android just said is not secure, and when, so a run of black frames asks once. */
    @Volatile private var notSecure: Pair<String, Long>? = null

    /** The display's width in pixels, for placing the private-tab covers on the frame. */
    @Volatile private var screenWidthPx = 1
    @Volatile private var settlingUntil = 0L

    /**
     * The newest frame this loop had to put off, and when it arrived. Handler thread only.
     *
     * A virtual display only produces a frame when the screen changes, so on a still screen the
     * last frame delivered is the last one there will be. It used to be dropped whenever the loop
     * was busy or had looked too recently — and the frame that arrives straight after the covers
     * come down is exactly one of those, because taking them down is what produced it. Dropped,
     * nothing was ever delivered again, and the pictures under those covers stayed on screen
     * uncovered until something else on the page moved: twelve seconds, the first time it was
     * measured. Held and looked at as soon as the loop is free, see [retryPending].
     */
    private var pendingImage: Image? = null
    private var pendingArrivedAt = 0L
    private val retryPending = Runnable {
        val image = pendingImage ?: return@Runnable
        pendingImage = null
        consider(image, pendingArrivedAt)
    }

    /**
     * Which session the frames arriving belong to.
     *
     * Creating a second projection makes Android stop the first, and the first one's callback
     * arrives afterwards. Without a token to compare, that late callback tears down the session
     * that replaced it, and capture dies a few seconds after a parent starts it for the second
     * time. This is exactly that bug, and the token is the fix.
     */
    @Volatile private var sessionToken = 0

    /**
     * When the screen went from uncovered to covered.
     *
     * Only the transition is timed. Re-recording the measurement while a cover is already up
     * would average in a stream of near-zero values and flatter the exposure figure into
     * meaninglessness, and that figure is the headline result of POC 1.
     */
    @Volatile private var coverRaisedAt = 0L

    /** Set when the parent stopped the session, so its ending is not reported as a failure. */
    @Volatile private var stoppedByParent = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedByParent = true
                scope.launch { preferences.setCaptureRequested(false) }
                stopCapture()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            State.setError("No capture consent was supplied.")
            stopSelf()
            return START_NOT_STICKY
        }

        // A second consent while a session is live replaces it rather than running alongside it.
        // Tearing the old one down here, deliberately, is what keeps its callback from arriving
        // later and taking the new session with it.
        if (projection != null) releaseSession()

        stoppedByParent = false
        // Written before the session starts rather than after, so a process killed seconds into
        // capture is still remembered as a session that was running. See the capture check in
        // MonitoringService, which is what notices.
        scope.launch { preferences.setCaptureRequested(true) }
        // Android requires the foreground service to be running before the projection is created.
        startAsForeground()
        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = notifier.monitoringNotification("Video blurring is watching")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SafetyNotifier.CAPTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(SafetyNotifier.CAPTURE_NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            State.setError("Android refused the projection: ${e.message}")
            stopSelf()
            return
        }

        if (projection == null) {
            State.setError("Capture consent was not granted.")
            stopSelf()
            return
        }

        this.projection = projection

        val thread = HandlerThread("screen-analysis").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        this.handler = handler

        sessionToken++
        val token = sessionToken

        // Android 14 requires a callback to be registered before the virtual display is created.
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // A callback from a session that has already been replaced says nothing about
                    // the one running now.
                    if (token != sessionToken) return
                    onSessionEnded()
                }
            },
            handler,
        )

        val metrics = displayMetrics()
        screenHeightPx = metrics.heightPixels.coerceAtLeast(1)
        screenWidthPx = metrics.widthPixels.coerceAtLeast(1)
        coversAnchoredScroll = signalStore.scroll.total()
        // Capturing at a fraction of screen resolution. Full resolution is not needed for a
        // classification verdict and is the single largest cost in the pipeline.
        val width = (metrics.widthPixels * CAPTURE_SCALE).roundToInt().coerceAtLeast(160)
        val height = (metrics.heightPixels * CAPTURE_SCALE).roundToInt().coerceAtLeast(160)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ onFrameAvailable(it) }, handler)

        virtualDisplay = try {
            projection.createVirtualDisplay(
                "child-safety-analysis",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (e: Exception) {
            State.setError("Could not create the virtual display: ${e.message}")
            stopCapture()
            return
        }

        // The offer to restart has been taken up, so it should not still be sitting there.
        notifier.clearCaptureResumeNotice()
        State.setRunning(width, height, TARGET_FPS)
    }

    private fun onFrameAvailable(reader: ImageReader) {
        // A newer frame supersedes one being held. Closed before acquiring, so the reader never
        // has more images out than it was created with.
        handler?.removeCallbacks(retryPending)
        pendingImage?.close()
        pendingImage = null

        // Sampling, not streaming. A couple of frames a second is enough to catch a scene while
        // it is still on screen, and everything between samples is deliberately discarded.
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        consider(image, arrivedAt = System.currentTimeMillis())
    }

    private fun consider(image: Image, arrivedAt: Long) {
        val now = System.currentTimeMillis()

        // Two rates, because deciding what to cover and keeping up with where it went are
        // different jobs with different costs. Classifying is expensive and its answer cannot
        // change faster than the content does. Following a scroll is a row profile and a window
        // move, and it has to happen at something closer to the rate a page actually moves: a
        // cover derived from a frame half a second old is placed where the picture used to be,
        // which is the lag a parent sees as the covers sliding around behind the page.
        val dueForFull = now - lastSampleAt >= (1000 / TARGET_FPS)
        val dueForTracking = patches.isNotEmpty() &&
            now - lastTrackAt >= (1000 / TRACK_FPS)

        // A browser in front is checked for a private tab at the tracking rate rather than the
        // classifying one. The check is a thousand pixels; waiting for the next full pass would
        // leave a private tab's pictures on screen for up to half a second before anything
        // covered them.
        val dueForPrivateCheck = dueForPrivateCheckSoon() &&
            now - lastTrackAt >= (1000 / TRACK_FPS)

        if (analysisInFlight || (!dueForFull && !dueForTracking && !dueForPrivateCheck)) {
            // Put off, not dropped: this may be the last frame the screen produces. Looked at
            // when the loop is free or the next pass is due, whichever this is waiting for, unless
            // a newer frame arrives first and takes its place.
            State.recordSkipped()
            pendingImage = image
            pendingArrivedAt = arrivedAt
            val wait = if (analysisInFlight) {
                BUSY_RETRY_MILLIS
            } else {
                val untilFull = lastSampleAt + 1000 / TARGET_FPS - now
                val untilTrack = if (patches.isNotEmpty() || dueForPrivateCheckSoon()) {
                    lastTrackAt + 1000 / TRACK_FPS - now
                } else {
                    untilFull
                }
                minOf(untilFull, untilTrack).coerceAtLeast(BUSY_RETRY_MILLIS)
            }
            handler?.postDelayed(retryPending, wait)
            return
        }

        lastTrackAt = now
        if (dueForFull) lastSampleAt = now
        analysisInFlight = true

        // The clock for the exposure measurement starts here, at the moment a frame reaches us,
        // and stops when the cover is actually up. Timing only the classifier would measure the
        // fastest part of the path and report it as the whole of it. See BlurLatency.
        val frameArrivedAt = arrivedAt

        // When the frame was composed, on the same clock scroll reports are stamped with, so the
        // page's position can be read for the moment this frame shows rather than for whenever it
        // gets processed. A timestamp that is not plausibly on that clock is not trusted.
        val frameTime = (image.timestamp / 1_000_000L).takeIf {
            kotlin.math.abs(SystemClock.uptimeMillis() - it) < 1_000L
        } ?: SystemClock.uptimeMillis()

        val bitmap = try {
            image.toBitmap()
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            analysisInFlight = false
            return
        }

        scope.launch {
            try {
                // A browser's private tab is a secure window too, and the one secure window this
                // protection is still answerable for: nothing in it can be judged, so it is handed
                // to the accessibility service, which can find its pictures without seeing them.
                // While it holds the screen this loop judges nothing — there is nothing to judge,
                // and the capture would show that service's covers (rule 17).
                if (notePrivateTab(bitmap, frameTime)) {
                    if (dueForFull) State.recordBlankFrame()
                    standDownForPrivateTab()
                    return@launch
                }
                // Brought forward only to look for a private tab, and there is none.
                if (!dueForFull && !dueForTracking) return@launch

                // A FLAG_SECURE window mirrors as pure black. Detecting that before classifying
                // is what separates "nothing unsafe here" from "we were not allowed to look".
                if (bitmap.isEffectivelyBlank()) {
                    if (dueForFull) State.recordBlankFrame()
                    return@launch
                }

                // Read once and share. Every stage below walks the same pixels, and reading them
                // out of the bitmap three times is the most expensive thing this loop could do
                // for no reason at all.
                val pixels = pixelsOf(bitmap)

                // What the scan leaves out is what is drawn now. What the motion measurements
                // leave out also includes where the covers were a moment ago, because the capture
                // can still be showing them there; see settlingRegions.
                val covered = drawnRegions
                val ownPaint = if (System.currentTimeMillis() < settlingUntil) {
                    covered + settlingRegions
                } else {
                    covered
                }

                // Where everything went, measured before anything else and on every frame this
                // service looks at. Excludes this app's own covers for the reason in rule 17:
                // they are painted at a fixed place while the page slides underneath them, so
                // including them would argue that nothing moved, most loudly when the most is
                // covered.
                val profile = RowProfile.of(pixels, bitmap.width, bitmap.height, ownPaint)

                // Whether this is a different screen, asked separately from how far this one
                // moved, and asked on a grid rather than on row averages. Collapsing the
                // horizontal axis is exactly right for measuring a scroll and throws away most of
                // what tells two screens apart, so the row test only ever recognised a navigation
                // that changed the screen's brightness as well. Everything else was carried on:
                // covers from the last page held over the next one, covers held over the app
                // switcher, until a hold timer let them go one at a time. See
                // SceneSignature.isNewLayoutVersus.
                val layout = SceneSignature.of(pixels, bitmap.width, bitmap.height, ownPaint)
                val newLayout = layout.isNewLayoutVersus(lastLayout)
                lastLayout = layout

                // Read on every frame this service looks at, not only the classifying ones, and
                // read even when it is about to be ignored: it advances the count it compares
                // against, and a reading skipped is a window change nothing ever notices.
                val windowChanged = windowChanged()

                val motion = motionOf(profile, newLayout, windowChanged, frameTime)
                lastProfile = profile

                // A tracking pass that finds a new layout promotes itself to a full one. The
                // covers on screen describe a page that is gone, and nothing but a classifier
                // pass can replace them; waiting for the next scheduled one would leave arbitrary
                // rectangles over new content, which is an obstruction where it lands and no
                // protection at all over whatever actually arrived.
                if (!dueForFull && !forceScan && motion !is FrameMotion.Changed) {
                    followScroll(motion)
                    return@launch
                }

                // The blurred copies move with their covers. Left where they were, none of them
                // fits its cover after a scroll, and a copy re-taken from this frame is a copy of
                // the cover standing there: the blur of a blur, see refreshPatches.
                if (motion is FrameMotion.Scrolled) {
                    patches = patches.mapNotNull { patch ->
                        patch.region.shiftedBy(motion.fraction)?.let { patch.copy(region = it) }
                    }
                }
                lastSampleAt = now
                State.recordFrame()

                val settings = preferences.settings.first()
                val blurEnabled = settings.protectionEnabled &&
                    settings.isEnabled(ProtectionFeature.VIDEO_BLURRING) &&
                    settings.videoBlurEnabled

                // Measured from the parts of the screen this app is not covering, because the
                // parts it is covering are its own output and are static by construction. This
                // answers only "is it worth looking again", which is a cheaper and twitchier
                // question than where anything went; RowProfile above answers that one.
                val signature = SceneSignature.of(pixels, bitmap.width, bitmap.height, ownPaint)
                val moved = signature.movedSince(lastSignature) || motion !is FrameMotion.Still
                lastSignature = signature

                val now = System.currentTimeMillis()
                // Taken from the fingerprint rather than from the rectangles, because the
                // question is how much screen is left to notice movement in, and overlapping
                // rectangles answer a different one. See SceneSignature.coveredShare.
                val coveredFraction = signature.coveredShare
                val dueForRescan = now - lastScanAt >= RESCAN_INTERVAL_MILLIS
                val dueForRecheck = CoverPolicy.anyDueForRecheck(activeCovers, now, coveredFraction)
                val shouldScan = moved || dueForRescan || dueForRecheck || forceScan

                // "Cover people" asks a different question from the other two modes, and routing it
                // through the safety classifier was answering the wrong one. A parent who chooses
                // to cover people is not asking which pictures are unsafe; they are asking for
                // people not to be shown, and an ordinary clothed photograph is exactly what they
                // mean. So in that mode the detector is the evidence and the classifier does not
                // run at all.
                val peopleMode = settings.blurMode == BlurMode.SUBJECTS

                val scan = if (shouldScan && !peopleMode) {
                    frameScanner.scan(
                        bitmap = bitmap,
                        pixels = pixels,
                        level = settings.blurProtectionLevel,
                        categories = settings.blurCategories,
                        ignore = covered,
                    ).also { State.recordScan(it) }
                } else {
                    null
                }

                val found: List<ScreenRegions.Region>? = when {
                    !shouldScan -> null
                    peopleMode -> peopleIn(bitmap, settings, covered)
                    else -> scan?.findings?.map { it.region }
                }

                if (shouldScan) {
                    // A look forced by a cover coming down is not spent on a frame that may still
                    // show that cover. It stays armed until the capture has certainly caught up,
                    // because on a still screen no later frame is coming to try again.
                    forceScan = forceScan && now < settlingUntil
                    lastScanAt = now
                }

                val before = activeCovers
                activeCovers = CoverPolicy.next(
                    current = before,
                    found = found,
                    motion = motion,
                    now = now,
                    coveredFraction = coveredFraction,
                )

                // Something came down. Whatever it was covering is visible again on the next
                // frame, and that frame has to be looked at rather than skipped.
                if (activeCovers.size < before.size) forceScan = true

                if (BuildConfig.DEBUG) logFrame(scan, found, motion)

                applyCover(bitmap, settings, blurEnabled, now)

                if (before.isEmpty() && activeCovers.isNotEmpty()) {
                    coverRaisedAt = System.currentTimeMillis()
                    State.recordBlurRaised()
                    State.recordExposure(
                        latencyMillis = coverRaisedAt - frameArrivedAt,
                        classifyMillis = scan?.worst?.durationMillis ?: 0L,
                    )
                }

                if (peopleMode && !found.isNullOrEmpty()) {
                    eventRepository.record(
                        type = SafetyEventType.UNSAFE_SCREEN,
                        title = if (found.size == 1) "A person was covered"
                        else "${found.size} people were covered",
                        // What was measured, named as what it is. The detector finds faces and
                        // infers a body around them; it does not know who anyone is, and nothing
                        // about the person is recorded. See rule 11.
                        detail = "${found.size} detected ${if (found.size == 1) "person" else "people"} " +
                            "covered on screen. No picture, crop or identity is stored.",
                        action = if (blurEnabled) EventAction.BLURRED else EventAction.LOGGED,
                        capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
                        dedupeWindowMillis = 60_000L,
                    )
                }

                // Logged on the frames that found something, not on every frame a cover happens
                // to still be up. The dedupe window does the rest.
                val worst = scan?.findings?.maxByOrNull { it.verdict.score }?.verdict
                if (worst != null) {
                    eventRepository.record(
                        type = SafetyEventType.UNSAFE_SCREEN,
                        title = if (blurEnabled) {
                            if (scan.findings.size == 1) "An image was covered"
                            else "${scan.findings.size} images were covered"
                        } else {
                            "Unsafe content detected"
                        },
                        // No frame, no crop, no thumbnail. Only the verdict and where it was.
                        detail = "%d of %d images on screen scored above the threshold. Worst %.2f. %s"
                            .format(
                                scan.findings.size,
                                scan.regionsExamined,
                                worst.score,
                                worst.explanation,
                            ),
                        action = if (blurEnabled) EventAction.BLURRED else EventAction.LOGGED,
                        capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
                        dedupeWindowMillis = 60_000L,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One bad frame must never end the session. Capture consent cannot be re-obtained
                // without a parent tapping a system dialog, so a crash here does not degrade the
                // protection, it ends it until somebody notices — and what they notice first is a
                // cover that stayed on screen with nothing left running to take it down. This is
                // the same reasoning that makes the labeller return an empty list rather than
                // throw, applied to the loop that calls it.
                Log.e(TAG, "Frame analysis failed; dropping this frame and continuing", e)
                State.recordAnalysisFailure()
            } finally {
                bitmap.recycle()
                analysisInFlight = false
            }
        }
    }

    /**
     * Puts the cover up in whichever shape the parent chose.
     *
     * [activeCovers] has already decided *what* is covered; this decides how it is drawn. Two of
     * the three modes draw the same way and differ only in what they cover, which is the right
     * shape for this to have: a whole screen is a different product from a patch, but a patch over
     * a picture and a patch over a person are the same thing pointed at different evidence.
     */
    private fun applyCover(
        bitmap: Bitmap,
        settings: SafetySettings,
        blurEnabled: Boolean,
        now: Long,
    ) {
        drawnStrength = settings.blurStrength
        if (!blurEnabled || activeCovers.isEmpty()) {
            clearCovers(settings.blurStrength)
            return
        }

        if (settings.blurMode == BlurMode.FULL_SCREEN) {
            patches = emptyList()
            drawRegions(listOf(WHOLE_SCREEN))
            overlayController.applyCover(patches = null, strength = settings.blurStrength)
            return
        }

        refreshPatches(bitmap, now, settings.blurStrength)
        // The copies were cut from the frame, where the pictures were when it was composed; the
        // windows go where the pictures are now.
        catchUpWithScroll()
        drawRegions(patches.map { it.region })
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "patches " + patches.joinToString { p ->
                    "[%.2f,%.2f,%.2f,%.2f]%s".format(
                        p.region.left, p.region.top, p.region.right, p.region.bottom,
                        if (p.preview == null) "!" else "",
                    )
                },
            )
        }
        overlayController.applyCover(
            patches = patches.map {
                CoveredPatch(area = it.region.toComposeRect(), preview = it.preview)
            },
            strength = settings.blurStrength,
        )
    }

    private fun dueForPrivateCheckSoon(): Boolean =
        BrowserSurfaces.isBrowser(signalStore.activeApp.value)

    /**
     * Whether a browser's private tab is in front, deciding it from this frame and publishing any
     * change. See PrivateTabTracker for why both a browser and a black window are needed, and
     * SecureFrame for why the private-tab covers are left out of the test.
     */
    private suspend fun notePrivateTab(bitmap: Bitmap, frameTime: Long): Boolean {
        val app = signalStore.activeApp.value
        val sx = bitmap.width.toFloat() / screenWidthPx
        val sy = bitmap.height.toFloat() / screenHeightPx
        val ignore = signalStore.recentPrivateCovers(frameTime).map { it.scaled(sx, sy) }
        val blackedOut = SecureFrame.isBlackedOut(bitmap.width, bitmap.height, ignore) { x, y ->
            val pixel = bitmap[x, y]
            (android.graphics.Color.red(pixel) +
                android.graphics.Color.green(pixel) +
                android.graphics.Color.blue(pixel)) / 3
        }
        // The store can end a tab on its own when another app comes into use, and start one when
        // the child returns to a browser they left while it was private; the tracker follows both.
        val now = SystemClock.uptimeMillis()
        val (published, proposed) = synchronized(privateTabs) {
            val published = signalStore.privateTab.value
            if (published == null && privateTabs.current != null) privateTabs.reset()
            if (published != null && privateTabs.current != published) privateTabs.adopt(published)
            published to privateTabs.next(
                foreground = app,
                foregroundIsBrowser = BrowserSurfaces.isBrowser(app) && !recentlyNotSecure(app, now),
                blackedOut = blackedOut,
                now = now,
            )
        }
        val vetoed = proposed != null && !verifyPrivateTab(proposed, isNew = proposed != published, now)
        val next = synchronized(privateTabs) {
            if (vetoed) {
                privateTabs.reset()
                if (BuildConfig.DEBUG) Log.d(TAG, "private tab $proposed refused: window not secure")
            }
            val next = privateTabs.current
            if (next != published) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "private tab $published -> $next: app=$app blackedOut=$blackedOut " +
                        "ignoring ${ignore.size} covers")
                }
                signalStore.setPrivateTab(next)
            }
            next
        }
        // Every frame replaces the pending check: a newer frame is newer evidence.
        privateStillCheck?.cancel()
        if (next != null && blackedOut == false) confirmPrivateTabStill()
        return next != null
    }

    /**
     * False when Android says [packageName]'s window is not secure, so the black frames that made
     * it look private were something else — a navigation, a tab switch, a black video. True when
     * Android says it is secure, and when Android cannot say, which keeps the frame evidence in
     * charge on versions without the probe (rule 12). A new tab is always asked about before its
     * covers go up; one already covered is asked again at most once per [PRIVATE_REVERIFY_MILLIS],
     * because a whole-page cover hides the page from the frames that would otherwise end it, and
     * a wrong one stood over an ordinary Chrome page until the app was restarted.
     */
    private suspend fun verifyPrivateTab(packageName: String, isNew: Boolean, now: Long): Boolean {
        if (!isNew && now - privateVerifiedAt < PRIVATE_REVERIFY_MILLIS) return true
        val secure = SafetyAccessibilityService.isActiveWindowSecure(packageName)
        if (BuildConfig.DEBUG) Log.d(TAG, "private tab $packageName: Android says secure=$secure")
        if (secure == false) {
            notSecure = packageName to now
            return false
        }
        if (secure == true) privateVerifiedAt = now
        return true
    }

    private fun recentlyNotSecure(packageName: String, now: Long): Boolean =
        notSecure?.let { (app, at) -> app == packageName && now - at < NOT_SECURE_MEMORY_MILLIS } == true

    /**
     * Ends a private tab on a screen that has gone still after a clear frame.
     *
     * A still screen sends no second frame (rule 28), so without this a tab left for a normal one
     * kept its covers until something moved. See PrivateTabTracker.confirmStill.
     */
    private fun confirmPrivateTabStill() {
        privateStillCheck = scope.launch {
            delay(PrivateTabTracker.EXIT_AFTER_MILLIS + STILL_CHECK_MARGIN_MILLIS)
            synchronized(privateTabs) {
                val before = signalStore.privateTab.value
                val after = privateTabs.confirmStill(SystemClock.uptimeMillis())
                if (before != null && after == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "private tab $before -> null: screen still")
                    signalStore.setPrivateTab(null)
                }
            }
        }
    }

    /**
     * Takes this loop's own covers down and forgets the screen, so nothing it placed on the
     * normal tab stands over the private one, and the tab it returns to is met afresh.
     */
    private fun standDownForPrivateTab() {
        if (activeCovers.isNotEmpty() || patches.isNotEmpty() || drawnRegions.isNotEmpty()) {
            activeCovers = emptyList()
            clearCovers(drawnStrength)
        }
        lastLayout = null
        lastSignature = null
        lastProfile = null
    }

    private fun clearCovers(strength: Float) {
        patches = emptyList()
        drawRegions(emptyList())
        overlayController.applyCover(patches = emptyList(), strength = strength)
    }

    /** Records what is now drawn, remembering what was drawn before it for [settlingRegions]. */
    private fun drawRegions(regions: List<ScreenRegions.Region>) {
        if (regions == drawnRegions) return
        settlingRegions = drawnRegions
        settlingUntil = System.currentTimeMillis() + COVER_SETTLE_MILLIS
        drawnRegions = regions
    }

    /** The frame's pixels, in the buffer this session reuses. See [pixelBuffer]. */
    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val size = bitmap.width * bitmap.height
        if (pixelBuffer.size != size) pixelBuffer = IntArray(size)
        bitmap.getPixels(pixelBuffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixelBuffer
    }

    /**
     * Whether a different app's window has come to the front since the last frame.
     *
     * Evidence from outside the capture, and the only evidence in this loop that is not an
     * inference about pixels. Screen capture cannot see an app; it sees a rectangle of colour, and
     * from that side an app switch and a cut in a video look more alike than two frames of the
     * same page do. The accessibility service is told which window is in front, so when it says
     * the window changed, every cover standing was judged somewhere else.
     *
     * Returns false on the first frame of a session rather than reading the first reading as a
     * change, and false throughout when the parent has not granted the accessibility service — the
     * count never moves and the grid does the work alone. Taking covers down on a window change
     * exposes nothing for longer than one frame: a new layout promotes the frame to a full pass,
     * so the covers the new window needs are derived from the same frame the old ones are dropped
     * on.
     */
    /**
     * How the page moved since the covers were placed, from the best evidence there is.
     *
     * In order: a window change, which ends every cover. Then the distance the app in front says
     * it scrolled, which is exact, needs nothing visible to be measured from, and is the only
     * measurement there is on a page whose pictures are all covered — see [ScrollLedger]. Then the
     * pixels, for apps that do not say.
     *
     * While the app is reporting a scroll, the pixels are not asked where anything went and the
     * grid is not asked whether this is a new page. The first would count the same movement
     * twice. The second is the reading that took every cover down at the end of each fling: a
     * page that has scrolled a long way since the last frame the loop looked at changes nearly
     * every cell, which is what a navigation looks like to a grid and not at all what it is.
     *
     * Small reported distances are left to accumulate rather than applied or dropped. Moving a
     * window by two pixels is not worth doing, and dropping them loses the page by two pixels a
     * frame through a slow scroll.
     */
    private fun motionOf(
        profile: RowProfile,
        newLayout: Boolean,
        windowChanged: Boolean,
        frameTime: Long,
    ): FrameMotion {
        val ledger = signalStore.scroll
        val frameScroll = ledger.totalAt(frameTime)
        val reported = frameScroll - coversAnchoredScroll
        val appScrolling = ledger.movedWithin(frameTime, APP_SCROLL_RECENT_MILLIS)

        if (windowChanged) {
            coversAnchoredScroll = frameScroll
            return FrameMotion.Changed
        }
        if (kotlin.math.abs(reported) >= MIN_APP_SCROLL_PX) {
            coversAnchoredScroll = frameScroll
            return FrameMotion.Scrolled(-reported.toFloat() / screenHeightPx, reported = true)
        }

        val measured = profile.motionFrom(lastProfile, newLayout && !appScrolling)
        if (appScrolling) return FrameMotion.Still
        // Anything the pixels act on re-bases the covers, so a report arriving late for the same
        // movement is not applied on top of it.
        if (measured is FrameMotion.Scrolled || measured is FrameMotion.Changed) {
            coversAnchoredScroll = frameScroll
        }
        return measured
    }

    /** Moves every cover and its copy by [fraction] of the screen, dropping any carried off it. */
    private fun shiftCovers(fraction: Float) {
        activeCovers = activeCovers.mapNotNull { cover ->
            cover.region.shiftedBy(fraction)?.let { cover.copy(region = it) }
        }
        patches = patches.mapNotNull { patch ->
            patch.region.shiftedBy(fraction)?.let { patch.copy(region = it) }
        }
    }

    /**
     * Moves the covers on by however far the app reports scrolling since they were placed.
     *
     * A frame is analysed some tens of milliseconds after it was composed, and the page does not
     * wait: a finger moves a feed further in that time than the width of a cover's edge. Placing
     * a cover where its picture was in the frame is placing it behind the picture, which is the
     * cover trailing the page. So the last thing before drawing is to bring every cover up to
     * where the page is now.
     */
    private fun catchUpWithScroll() {
        val pending = signalStore.scroll.total() - coversAnchoredScroll
        if (kotlin.math.abs(pending) < MIN_APP_SCROLL_PX) return
        coversAnchoredScroll += pending
        shiftCovers(-pending.toFloat() / screenHeightPx)
    }

    /** A tracking pass that measured no scroll still owes the covers any the app has reported. */
    private fun catchUpAndRedraw() {
        if (patches.isEmpty()) return
        val before = patches
        catchUpWithScroll()
        if (patches == before) return
        drawRegions(patches.map { it.region })
        overlayController.applyCover(
            patches = patches.map {
                CoveredPatch(area = it.region.toComposeRect(), preview = it.preview)
            },
            strength = drawnStrength,
        )
    }

    private fun windowChanged(): Boolean {
        val changes = signalStore.foreground.value.changes
        val changed = lastWindowChanges >= 0 && changes != lastWindowChanges
        lastWindowChanges = changes
        return changed
    }

    /**
     * Carries the covers along with the page, without re-deciding anything.
     *
     * This is the cheap half of the loop and it exists because the expensive half cannot run often
     * enough. A classifier pass a couple of times a second is plenty to decide *what* to cover; it
     * is nowhere near enough to decide *where*, because a finger moves a feed further in 200ms
     * than the height of the thing being covered. Between passes, then, nothing is judged: the
     * page's displacement is measured and every cover is moved by it, which is a window position
     * change and no classification at all.
     *
     * Nothing here can raise a cover or take one down. A cover that scrolls off the screen is
     * dropped because there is no longer anywhere to put it, and that is the only way the set
     * changes. Deciding remains in one place, on the frames that looked.
     */
    private fun followScroll(motion: FrameMotion) {
        val scrolled = motion as? FrameMotion.Scrolled ?: return catchUpAndRedraw()
        // Whole-screen mode draws one cover the size of the display, so there is nothing a scroll
        // could move. Its covers are live in activeCovers but it keeps no patches.
        if (patches.isEmpty()) return

        shiftCovers(scrolled.fraction)
        catchUpWithScroll()
        drawRegions(patches.map { it.region })

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "followed %+.3f%s covers=${patches.size}".format(
                    scrolled.fraction,
                    if (scrolled.reported) " reported" else "",
                ),
            )
        }

        overlayController.applyCover(
            patches = patches.map {
                CoveredPatch(area = it.region.toComposeRect(), preview = it.preview)
            },
            strength = drawnStrength,
        )
    }

    /**
     * Keeps each cover's blurred copy of what is underneath it.
     *
     * The rule is that a preview may only be taken on the frame its picture was actually seen on.
     * Every other frame the capture shows this app's own cover in that place, and sampling it would
     * blur the blur: after a few seconds the patch is a flat rectangle, which is the solid block
     * the whole design is trying not to be. So a cover confirmed this frame gets a fresh preview,
     * and one standing on older evidence keeps the preview it already had.
     */
    private fun refreshPatches(bitmap: Bitmap, now: Long, strength: Float) {
        val previous = patches
        // Decided as rectangles that may overlap; drawn as rectangles that do not. The covering is
        // identical either way — the trimming only ever gives up ground a neighbouring cover
        // already holds — but overlapping windows on the untrusted route compound past the
        // system's occlusion limit and every touch on the seam is dropped. See
        // ScreenRegions.withoutOverlaps.
        val drawable = ScreenRegions.withoutOverlaps(activeCovers.map { it.region })
        patches = drawable.map { region ->
            val cover = activeCovers.maxByOrNull { it.region.overlapWith(region) }
            val carried = previous
                .maxByOrNull { it.region.overlapWith(region) }
                ?.takeIf { it.region.overlapWith(region) >= CoverPolicy.SAME_REGION_OVERLAP }

            // A preview taken for essentially this rectangle is kept rather than taken again. Two
            // samples of the same picture are not identical — the page moves a little, the capture
            // quantises differently — so re-sampling swaps the blur for a slightly different blur
            // every time the cover is re-confirmed, which is a shimmer over the one thing on the
            // page that is supposed to sit still.
            val fits = carried?.preview != null && CoverPolicy.mayReusePreview(
                raisedAt = cover?.raisedAt ?: 0L,
                now = now,
                carriedOverlap = carried.region.overlapWith(region),
            )

            when {
                fits -> Patch(region, carried?.preview)
                // A cover that has just gone up takes its own copy or goes without one. It may
                // have landed exactly where the last one stood — a page turns and the new page's
                // first thumbnail is in the same place — and keeping what was drawn there paints
                // the previous page's photograph over a picture nobody has looked at.
                cover?.lastConfirmedAt == now || cover?.raisedAt == now ->
                    Patch(region, previewOf(bitmap, region, strength))
                else -> Patch(region, carried?.preview)
            }
        }
    }

    /**
     * A blurred copy of one region of the frame.
     *
     * Returns null rather than throwing. A patch with no preview still covers its picture with a
     * wash, so a failure here costs the softer appearance and never the protection.
     */
    private fun previewOf(
        bitmap: Bitmap,
        region: ScreenRegions.Region,
        strength: Float,
    ): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val left = (region.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (region.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (region.right * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (region.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)

        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        // blurPreview, not blur. The browser's blur crushes an image to a few pixels because the
        // original never reaches the child there; this one is drawn over a screen the child is
        // already looking at and has to read as that picture out of focus rather than as a panel
        // laid on top of it. See the two doc comments on ImageBlur.
        val blurred = imageBlur.blurPreview(crop, strength)
        if (blurred !== crop) crop.recycle()
        blurred.asImageBitmap()
    }.onFailure { if (BuildConfig.DEBUG) Log.d(TAG, "preview failed: $it") }.getOrNull()

    /**
     * The people on screen the parent asked to cover.
     *
     * Not filtered by any safety verdict, which is the whole point of the mode. Already-covered
     * ground is excluded because a covered person cannot be detected under their own cover, and
     * counting them as absent would take the cover straight back down.
     */
    private suspend fun peopleIn(
        bitmap: Bitmap,
        settings: SafetySettings,
        covered: List<ScreenRegions.Region>,
    ): List<ScreenRegions.Region> {
        val people = subjectDetector.detect(bitmap)
            .filter { it.shouldCover(settings.blurSubjects) }
            .map { subject ->
                ScreenRegions.Region(
                    left = subject.bounds.left / bitmap.width.toFloat(),
                    top = subject.bounds.top / bitmap.height.toFloat(),
                    right = subject.bounds.right / bitmap.width.toFloat(),
                    bottom = subject.bounds.bottom / bitmap.height.toFloat(),
                )
            }
            .filterNot { person -> covered.any { person.containedBy(it) >= 0.6f } }

        // Not counted as a fallback when empty. In this mode an empty result means there is
        // nobody on screen, which is the ordinary case, not a detector that failed to find
        // someone in a picture that needed covering.
        if (people.isNotEmpty()) State.recordSubjectsCovered(people.size)
        return people
    }

    private fun ScreenRegions.Region.toComposeRect() = androidx.compose.ui.geometry.Rect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    /**
     * One line per sampled frame, debug builds only.
     *
     * Kept because every threshold in the region pipeline has to be calibrated against real
     * screens rather than reasoned about, and without this the only visible output is whether a
     * cover appeared. Debug-only and never written anywhere: the labels a model returned about a
     * child's screen are not something to leave on a device. Rule 3 governs the event log, and
     * this is not that; it is a development instrument and it ships disabled.
     */
    private fun logFrame(
        scan: FrameScanner.Scan?,
        found: List<ScreenRegions.Region>?,
        motion: FrameMotion,
    ) {
        if (scan == null) {
            Log.d(
                TAG,
                "frame $motion people=${found?.size ?: -1} covers=${activeCovers.size}",
            )
            return
        }
        Log.d(
            TAG,
            "frame $motion pictures=${scan.regionsExamined} " +
                "wholeFrame=${scan.wholeFrame} failed=${scan.findings.size} " +
                "covers=${activeCovers.size} worst=%.2f labels=[%s]".format(
                    scan.worst?.score ?: 0f,
                    scan.worst?.labels?.joinToString { "${it.text} %.2f".format(it.confidence) }
                        ?: "",
                ),
        )
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    /**
     * The session ended on Android's terms rather than ours.
     *
     * This is the case the old code handled worst. Consent lasts one session and cannot be held,
     * so a session ending is normal and frequent; what is not acceptable is it ending silently,
     * leaving a protection screen saying "not watching" and no way back to it from where the
     * parent actually is. So it is recorded as an event and offered back as a notification that
     * opens the consent dialog directly.
     */
    private fun onSessionEnded() {
        val framesSeen = State.metrics.value.framesAnalysed
        State.setStopped(
            "The capture session ended. Android requires fresh consent for every session, so " +
                "this cannot resume on its own.",
        )
        releaseSession()

        if (!stoppedByParent) {
            scope.launch {
                val settings = preferences.settings.first()
                if (!settings.isEnabled(ProtectionFeature.VIDEO_BLURRING)) return@launch

                notifier.postCaptureResumeNotice()
                eventRepository.record(
                    type = SafetyEventType.MONITORING_STOPPED,
                    title = "Video blurring stopped watching",
                    detail = "The screen capture session ended after judging $framesSeen frames. " +
                        "Android hands out capture consent one session at a time and no app can " +
                        "hold it, so video is not being judged until a parent starts it again.",
                    action = EventAction.NOT_ENFORCEABLE,
                    capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
                    dedupeWindowMillis = 5 * 60_000L,
                )
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture() {
        State.setRunning(false)
        releaseSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Everything the session holds, released in an order that cannot leave the screen covered. */
    private fun releaseSession() {
        // A stopped session must never leave the screen covered with nothing running to lift it.
        // That includes the private-tab covers: nothing would be left to say the tab had closed.
        overlayController.clearCover()
        privateStillCheck?.cancel()
        signalStore.setPrivateTab(null)
        synchronized(privateTabs) { privateTabs.reset() }
        activeCovers = emptyList()
        lastSignature = null
        lastProfile = null
        lastLayout = null
        lastWindowChanges = -1L
        lastScanAt = 0
        lastTrackAt = 0
        forceScan = false
        // What the scanner remembers about each picture is about this screen in this session. A
        // verdict carried into the next one would be a verdict about pixels nobody has looked at.
        frameScanner.reset()
        patches = emptyList()
        drawnRegions = emptyList()
        settlingRegions = emptyList()
        settlingUntil = 0
        handler?.removeCallbacks(retryPending)
        runCatching { pendingImage?.close() }
        pendingImage = null
        coverRaisedAt = 0
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        State.setRunning(false)
    }

    /**
     * The child swiping the app out of recents must not be how video blurring is switched off.
     *
     * A foreground service is not required to stop with its task and this one does not: the
     * capture session, the covers and the classifier all keep running, and only the window the
     * parent looks at has gone. Nothing is torn down here on purpose, and the manifest says
     * `stopWithTask="false"` so that stays true on builds that would otherwise decide for us.
     *
     * The event is recorded anyway, because on manufacturers' builds that kill the process
     * regardless this is the last thing that runs, and a parent is owed the difference between a
     * protection they switched off and one a child got rid of. Capture cannot restart itself after
     * that: Android hands out capture consent one session at a time, tied to the process that
     * asked, so a killed process cannot replay the consent it was given. What closes the gap is
     * the offer to resume, which MonitoringService raises as soon as it notices.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (projection != null) {
            scope.launch {
                eventRepository.record(
                    type = SafetyEventType.MONITORING_STOPPED,
                    title = "The app was swiped away while video blurring was on",
                    detail = "Capture keeps running here, but several manufacturers' builds kill " +
                        "the app with the task. Android will not re-issue capture consent to a " +
                        "process it killed, so if that happened video blurring is off until a " +
                        "parent starts it again.",
                    action = EventAction.NOT_ENFORCEABLE,
                    capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
                    dedupeWindowMillis = 60 * 60_000L,
                )
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Live measurements from the capture session, shown in the screen capture lab. */
    object State {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        private val _metrics = MutableStateFlow(CaptureMetrics())
        val metrics: StateFlow<CaptureMetrics> = _metrics.asStateFlow()

        fun setRunning(width: Int, height: Int, targetFps: Int) {
            _running.value = true
            _message.value = null
            _metrics.value = CaptureMetrics(
                captureWidth = width,
                captureHeight = height,
                targetFps = targetFps,
                startedAt = System.currentTimeMillis(),
            )
        }

        fun setRunning(value: Boolean) { _running.value = value }

        fun setError(message: String) {
            _running.value = false
            _message.value = message
        }

        fun setStopped(message: String) {
            _running.value = false
            _message.value = message
        }

        /** A frame was sampled and was not blank. Says nothing about whether it was classified. */
        fun recordFrame() = _metrics.update {
            it.copy(
                framesAnalysed = it.framesAnalysed + 1,
                lastFrameAt = System.currentTimeMillis(),
            )
        }

        /**
         * A frame where the pictures were actually found and classified.
         *
         * Counted apart from [recordFrame] because the two answer different questions, and
         * merging them would make the timing figure meaningless. Most sampled frames are skipped
         * on purpose: the screen had not moved, so the previous answer still stands. Averaging
         * the classifier's cost over those would report a speed the classifier never achieved.
         */
        fun recordScan(scan: FrameScanner.Scan) = _metrics.update {
            val score = scan.worst?.score ?: 0f
            it.copy(
                framesScanned = it.framesScanned + 1,
                regionsExamined = it.regionsExamined + scan.regionsExamined,
                wholeFrameScans = it.wholeFrameScans + if (scan.wholeFrame) 1 else 0,
                totalAnalysisMillis = it.totalAnalysisMillis + (scan.worst?.durationMillis ?: 0L),
                highestScore = maxOf(it.highestScore, score),
                lastScore = score,
            )
        }

        fun recordSkipped() = _metrics.update { it.copy(framesSkipped = it.framesSkipped + 1) }

        /**
         * A frame that threw.
         *
         * Counted rather than swallowed. A session that is quietly failing on every frame looks
         * identical to a session watching a safe screen, and telling those apart is the whole
         * reason this app has an Unavailable state at all.
         */
        fun recordAnalysisFailure() =
            _metrics.update { it.copy(analysisFailures = it.analysisFailures + 1) }

        fun recordBlurRaised() = _metrics.update { it.copy(blurEvents = it.blurEvents + 1) }

        /** An unsafe frame where no subject was found, so the cover fell back to the whole screen. */
        fun recordSubjectFallback() =
            _metrics.update { it.copy(subjectFallbacks = it.subjectFallbacks + 1) }

        fun recordSubjectsCovered(count: Int) = _metrics.update {
            it.copy(
                subjectCovers = it.subjectCovers + 1,
                subjectsCovered = it.subjectsCovered + count,
            )
        }

        /** The whole frame-arrival to cover-raised window, which is what POC 1 has to report. */
        fun recordExposure(latencyMillis: Long, classifyMillis: Long) = _metrics.update {
            it.copy(latency = it.latency.record(latencyMillis, classifyMillis))
        }

        fun recordBlankFrame() = _metrics.update {
            it.copy(
                blankFrames = it.blankFrames + 1,
                framesAnalysed = it.framesAnalysed + 1,
                lastFrameAt = System.currentTimeMillis(),
            )
        }

        fun reset() { _metrics.value = CaptureMetrics(); _message.value = null }

        data class CaptureMetrics(
            val captureWidth: Int = 0,
            val captureHeight: Int = 0,
            val targetFps: Int = 0,
            val framesAnalysed: Int = 0,
            val framesSkipped: Int = 0,
            val blankFrames: Int = 0,
            val blurEvents: Int = 0,
            val totalAnalysisMillis: Long = 0,
            val highestScore: Float = 0f,
            val lastScore: Float = 0f,
            val startedAt: Long = 0,
            val lastFrameAt: Long = 0,
            /** Exposure measurement. The headline result of POC 1. */
            val latency: BlurLatency = BlurLatency.initial(1000L / TARGET_FPS),
            /** Frames where the pictures were located and classified, rather than skipped. */
            val framesScanned: Int = 0,
            /** How many individual pictures have been judged, which is the unit that matters. */
            val regionsExamined: Int = 0,
            /**
             * Scans that found nothing picture-shaped and judged the whole frame instead.
             *
             * A high share means region proposal is not finding structure in whatever is being
             * watched, so the feature is quietly behaving like the old whole-screen version.
             */
            val wholeFrameScans: Int = 0,
            /** Frames dropped because something threw. Above zero means look at the log. */
            val analysisFailures: Int = 0,
            val subjectCovers: Int = 0,
            val subjectsCovered: Int = 0,
            /**
             * Unsafe frames where subject mode found nobody.
             *
             * The figure that decides whether region blur is viable in production. A high share
             * means the detector is missing the subjects and the mode is quietly degrading to
             * whole-screen covering most of the time.
             */
            val subjectFallbacks: Int = 0,
        ) {
            val averageAnalysisMillis: Long
                get() = if (framesScanned == 0) 0 else totalAnalysisMillis / framesScanned

            /** Average number of pictures found on a screen. Below one means proposal is failing. */
            val regionsPerScan: Float
                get() = if (framesScanned == 0) 0f else regionsExamined.toFloat() / framesScanned

            /** Share of sampled frames that came back black, meaning capture was blocked. */
            val blankShare: Float
                get() = if (framesAnalysed == 0) 0f else blankFrames.toFloat() / framesAnalysed

            /** Share of subject-mode covers that had to fall back to the whole screen. */
            val subjectFallbackShare: Float
                get() {
                    val attempts = subjectCovers + subjectFallbacks
                    return if (attempts == 0) 0f else subjectFallbacks.toFloat() / attempts
                }

            val effectiveFps: Float
                get() {
                    val elapsed = (lastFrameAt - startedAt).coerceAtLeast(1)
                    return framesAnalysed * 1000f / elapsed
                }
        }
    }

    companion object {
        private const val TAG = "ScreenAnalysis"

        /** A little past the tracker's window, so the check never lands a frame early. */
        private const val STILL_CHECK_MARGIN_MILLIS = 50L

        /** How often a covered private tab is checked again with Android; see verifyPrivateTab. */
        private const val PRIVATE_REVERIFY_MILLIS = 1_000L

        /**
         * How long a browser Android said is not secure is not asked about again. Short, because
         * switching to a real incognito tab inside that time is covered once it runs out.
         */
        private const val NOT_SECURE_MEMORY_MILLIS = 1_500L

        const val ACTION_STOP = "com.example.childsafetyimpl.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /**
         * Two frames per second. Fast enough to cover a scene while it is still playing, slow
         * enough that continuous capture does not dominate the battery.
         *
         * This is the rate at which the screen is *judged*. It is not the rate at which the
         * covers move; see [TRACK_FPS].
         */
        private const val TARGET_FPS = 2

        /**
         * How often the covers are allowed to follow the page, between judgements.
         *
         * A separate and much higher rate, because the two jobs cost different amounts and have
         * different deadlines. Deciding what to cover means running a classifier over every
         * picture-shaped region of the frame. Following the page means averaging each row and
         * sliding one array over another — cheap enough to do five times as often, and it has to
         * be, because a feed under a moving finger travels the height of a thumbnail in the gap
         * between two judgements. At two frames per second the covers were placed from a frame up
         * to half a second old, which on a scrolling page means placed where the picture was.
         *
         * Only runs while something is covered. Nothing on screen, nothing to follow.
         */
        private const val TRACK_FPS = 10


        /**
         * What fraction of the display is captured.
         *
         * Not only a cost setting: it is the resolution every verdict in this feature is taken at.
         * The labeller works from a 224-pixel input, and a thumbnail in a grid is a fraction of
         * the capture, so at a third of screen resolution a typical thumbnail reached the model as
         * something like 120 pixels *upscaled* to 224 — an interpolated picture with no detail
         * that was not already gone. The model's answers on those crops are correspondingly
         * unreliable, which shows up as verdicts that differ between two frames of the same thing,
         * and that is what [com.example.childsafetyimpl.platform.content.VerdictStability] then
         * has to smooth over. Capturing at half resolution puts a thumbnail above the model's
         * input size, so the crop is scaled *down* into it with a filter instead, which is the
         * direction that preserves detail.
         *
         * Paid for in pixels moved per frame rather than in model time: the crop handed to the
         * classifier is the same size either way.
         */
        private const val CAPTURE_SCALE = 0.5f

        /**
         * The longest a verdict stands without being re-taken, even on a motionless screen.
         *
         * The scene fingerprint decides when to look again, and this is what covers the case
         * where it is wrong: a video that changes too little to register, a page that loads its
         * images late, an animation that moves under a cover. Without it, one missed change is
         * permanent for the session.
         */
        private const val RESCAN_INTERVAL_MILLIS = 2_500L

        /**
         * How long a cover's old position is still left out of the motion measurements after it
         * changes. A few frames of composition, generously: the cost of too long is a strip of
         * page not compared for a moment, and the cost of too short is the covers reading their
         * own movement as a navigation and coming down. See [settlingRegions].
         */
        private const val COVER_SETTLE_MILLIS = 400L

        /**
         * The smallest reported scroll worth moving a window for, in screen pixels. Smaller
         * distances accumulate until they add up to this; see motionOf.
         */
        private const val MIN_APP_SCROLL_PX = 4L

        /**
         * How recently the app must have reported a scroll for the page to count as scrolling.
         * Reports come every hundred milliseconds or so while a page moves; this is a few of
         * them, so the gap between two reports is never mistaken for the page having stopped.
         */
        private const val APP_SCROLL_RECENT_MILLIS = 400L

        /** How soon a frame put off because the loop was busy is looked at again. */
        private const val BUSY_RETRY_MILLIS = 50L

        private val WHOLE_SCREEN = ScreenRegions.Region(0f, 0f, 1f, 1f)

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenAnalysisService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenAnalysisService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

/**
 * Copies an ImageReader plane into a bitmap of exactly the size of the screen.
 *
 * A capture plane's rows are padded out to a hardware-friendly stride, so the buffer is wider than
 * the display and the extra columns on the right hold nothing: not black, not the edge of the
 * screen, just whatever the allocation came with, usually fully transparent. The only way to copy
 * the buffer in one call is to make a bitmap that includes the padding — and then the padding has
 * to be cut off again, which is the part that was missing.
 *
 * Leaving it on is a quiet, disproportionate bug, because every coordinate in this pipeline is a
 * fraction of the frame's width. A frame ten percent wider than the display puts every fraction
 * ten percent too far left by the time it is drawn, so each cover sits beside the picture it was
 * measured on rather than over it. And a region near the right edge takes the transparent padding
 * into its blur, which produces a preview that is partly transparent: drawn over the screen, the
 * covered picture shows straight through its own cover. A cover that is both offset and see-through
 * is not read as a blur at all. It is read as a shaded panel dropped on the page, which is exactly
 * how it was reported.
 *
 * The bitmap is also marked opaque, because a screen capture is: there is nothing behind a display.
 */
private fun android.media.Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    val padded = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888,
    )
    padded.copyPixelsFromBuffer(buffer)

    if (padded.width == width) {
        padded.setHasAlpha(false)
        return padded
    }

    val exact = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    exact.setHasAlpha(false)
    return exact
}

/**
 * A frame is treated as blank when almost every sampled pixel is near-black.
 *
 * This is how FLAG_SECURE presents itself to a projection: not as an error, but as a perfectly
 * ordinary frame that happens to be entirely black.
 */
private fun Bitmap.isEffectivelyBlank(): Boolean {
    val stepX = (width / 24).coerceAtLeast(1)
    val stepY = (height / 24).coerceAtLeast(1)
    var sampled = 0
    var dark = 0

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = getPixel(x, y)
            val luminance = (android.graphics.Color.red(pixel) +
                android.graphics.Color.green(pixel) +
                android.graphics.Color.blue(pixel)) / 3
            if (luminance < 12) dark++
            sampled++
            x += stepX
        }
        y += stepY
    }
    return sampled > 0 && dark.toFloat() / sampled > 0.985f
}
