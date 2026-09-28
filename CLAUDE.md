# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # debug APK
./gradlew installDebug         # install on a connected device or emulator
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # lint
./gradlew clean
```

Requires JDK 21 and Android SDK 37. Min SDK 24, target SDK 37, Java 11 compatibility.
There are no product flavours and no backend.

Ten protections, listed in `core/protection/ProtectionFeature`.

## Architecture

**MVVM + UseCase** with Jetpack Compose UI and Hilt DI, following the same conventions as the
other Android apps in this workspace.

### Layer structure

```
core/          protection catalog and live status, permission model, result wrapper, time helpers
data/          Room entities and DAOs, DataStore preferences, repository implementations, seed lists
domain/        repository interfaces and domain models
platform/      the real Android mechanisms, one package per privileged capability
di/            AppModule, DatabaseModule, CoroutinesModule, RepositoryModule
navigation/    ViewModelNav, route definitions, the graph
ui/            theme, design system, illustrations, motion, the child-facing overlay content
ux/            feature screens: Screen + UiState + UseCase + ViewModel
```

There is no backend, so the data layer is Room + DataStore rather than Retrofit, and `DataResult`
plays the role `NetworkResult` plays in a networked app. It has an extra `Unavailable` case,
because "Android will not tell us" is a distinct outcome from "it went wrong", and the UI has to
say so rather than showing a plausible zero.

### Key patterns

**ViewModel + Navigation**: ViewModels implement `ViewModelNav by ViewModelNavImpl()`. Navigation
actions flow through `navigationActionFlow` as a `SharedFlow`, consumed by the `HandleNavigation`
composable at the top of each Screen. ViewModels never hold a `NavController`.

**UseCase as UI state manager**: UseCases (`GetDashboardUiStateUseCase`,
`GetVpnProtectionUiStateUseCase`, …) own the `MutableStateFlow` of UI state and do the work.
ViewModels are thin: they expose the UseCase's state and translate `UiEvent`s.

**Events**: each feature has a sealed `…UiEvent` interface and a single `onEvent` entry point.

**Dispatchers**: qualified `@IoDispatcher` / `@DefaultDispatcher` / `@MainDispatcher`.
`@ApplicationScope` is the scope for work that must outlive any screen, such as the services.

## The protection catalog

`core/protection/ProtectionFeature` is the single registry of what the app protects. Every feature
screen, dashboard tile and enforcement decision resolves to an entry in it, and each entry declares
the permissions it *requires* and the ones that merely *strengthen* it.

`ProtectionStatusProvider` turns that plus live grant state into a `ProtectionStatus`. One class
owns the question so a card, the dashboard and the engine can never disagree.

The distinction that matters most is `ProtectionState.NEEDS_PERMISSION` versus `OFF`: a protection
the parent switched on that cannot run must never look like one they chose to leave off.

**When adding a protection**: add the enum entry, add a route in `Routes.screenFor`, add an icon in
`ProtectionsScreen.icon`, and add the screen. `ProtectionCatalogTest` enforces that every feature
has a route, unique id, and no permission listed as both required and strengthening.

## What each protection is actually built on

| Protection | Mechanism |
|---|---|
| VPN restriction | `VpnGuard` + `ChildSafetyVpnService`. Finds VPN apps by querying the VPN service intent, closes them on open, and reclaims the single tunnel Android allows. |
| Dual and cloned app control | `CloneGuard` + `CloneDetector`. Four scans, a `LauncherApps` callback for live detection, findings persisted so history survives. |
| Shorts and Reels control | `SafetyAccessibilityService` + `SurfaceSignatures` + `SurfaceGuard`. Reads the live view tree, matches a signature, presses back. |
| Status and Stories control | Same engine, different signatures. |
| AI chat and game monitoring | `AiActivityMonitor` for time and budgets, `AiPromptRiskScorer` for the conversation surface. |
| App restriction | `MonitoringService` polls `UsageStatsManager` and shows `BlockOverlayController`'s block screen. |
| Search and site filtering | `SafeSearchDns` rewrites search-engine lookups in the tunnel to each engine's published filtered address, and `SearchTermGuard` reads the address bar and search fields of other browsers against `BlockedSearchTerms`. Sites are judged by `SafetyRulesRepository.evaluateHost`: the parent's rules, the Room category rules, then the bundled category lists (`BundledHostLists`, ~64k adult hosts held as a `HostSet`) and `AdultHostKeywords`. |
| Ad removal | `AdBlockList` at three layers: DNS in the tunnel, request interception in the browser and player, CSS to hide the slots. |
| Browser image blurring | `SafeBrowserEngine` + `ImageSafetyClassifier` + `ImageBlur`. Images are fetched, scored and blurred before the page receives them. |
| Video blurring | `ScreenAnalysisService` samples frames through `MediaProjection`. `ScreenRegions` finds the picture-shaped parts of the frame, `FrameScanner` classifies each one separately, `CoverPolicy` decides which covers stay up, `VerdictStability` keeps one picture's verdict steady across the frames it appears in, `RowProfile` measures how far the page moved so the covers move with it — or, where the app in front reports its own scroll through the accessibility service, `ScrollLedger` says exactly how far — `SceneSignature` decides whether it is still the same screen at all, with the accessibility service's foreground-window signal as the authority when it is granted, and `BlockOverlayController` gives each covered picture its own window — added through the accessibility service so it can be opaque and still let touches through — holding a blurred copy of it (`ImageBlur.blurPreview`) and nothing else. Covering the individual failing pictures is the default; whole-screen and cover-people are the other two modes. A browser's private tab cannot be captured at all, so it is handed to `PrivateTabGuard` (rule 31). |
| Internet pause | `InternetPauseController` switches `ChildSafetyVpnService` into `TunnelMode.PAUSED`: a default route with nothing forwarded. Persisted before it is applied, restored on boot, and read back by the tunnel on every start (`TunnelMode.restingFor`). `MonitoringService` re-applies a pause the tunnel lost, shows the child a paused notice over network-dependent apps, and alerts the parent when the pause cannot be put back. One tap from the dashboard and from Controls. |

## Sprint 0 additions

Five subsystems were added for the Sprint 0 POCs. Each carries its reasoning in its own KDoc; what
follows is where to look.

- **`platform/validation/BypassScenario.kt`** — the 35-scenario bypass catalogue, held as data so
  the limits stated on a protection screen and the limits validated in the POC report come from one
  source. `BypassCatalogTest` enforces that nothing is classified as prevented without a mechanism,
  and that no tampering scenario is ever claimed as prevented.
- **`platform/network/FilteringMode.kt`** — Private DNS vs tunnel vs hybrid, with a capability
  matrix. Added because the client asked why a DNS-based product felt lighter than a VpnService one;
  the answer is that the two products do different jobs, and only the tunnel can pause the internet.
  The parent chooses the posture on Controls (`SafetySettings.filteringMode`, default tunnel so an
  update changes nobody's filtering). In the Private DNS postures the tunnel runs only for a pause,
  or as an empty `TunnelMode.HOLD` for fifteen seconds to end another VPN, because a filtering
  tunnel would refuse the resolver Private DNS points at. `PrivateDnsGuard` drives the guided setup
  and the monitoring loop's alert when the setting is cleared or pointed elsewhere.
- **`platform/network/SystemTrafficExemptions.kt`** — the hosts a phone needs to keep working
  (connectivity checks, push, Play updates, sign-in, time), kept reachable in allow-list-only mode.
  Consulted after the parent's rules, so it never outranks a block. Never add a browsable host or a
  general-purpose CDN; a test enforces the ones that matter most.
- **`platform/network/EncryptedDnsPolicy.kt`** — DoH and DoT defences at two layers. Refusing
  resolver hostnames is defeated by configuring a resolver by IP, so the tunnel also takes a host
  route per known resolver address and drops what is sent there.
- **`platform/network/TunnelExclusions.kt`** — banking and identity apps kept outside the tunnel.
  Not a filtering decision: many of them refuse to run when they can see `TRANSPORT_VPN`, and a
  parent whose bank stops working uninstalls the product. Never extend this list to browsers or
  social apps; a test enforces it.
- **`core/protection/ProtectionHealth.kt`** — the four-state health model. `DISABLED` and `TAMPERED`
  are deliberately not merged: one is a parent's decision and the other is a child's. Rendered on
  the dashboard by `GetDashboardUiStateUseCase`, which feeds it from `MonitoringService.State`.
  Accessibility withdrawn while Android's Advanced Protection Mode is on
  (`platform/admin/AdvancedProtectionProbe.kt`) is the operating system's doing and is reported as
  its own issue, never as tampering.
- **`platform/content/ScreenRegions.kt`**, **`SceneSignature.kt`**, **`FrameMotion.kt`**,
  **`CoverPolicy.kt`**, **`FrameScanner.kt`**, **`VerdictStability.kt`** — the region covering
  pipeline, added after driving video blurring on a device. Each file's KDoc carries the defect it
  exists to prevent; `RegionCoveringTest` and `CoverStabilityTest` hold the behaviour. The short
  version: the unit of judgement is one picture, not one screen; a cover is released by evidence
  from the *uncovered* part of the screen rather than by re-classifying the ground it is standing
  on; a scroll moves the covers rather than discarding them; and neither where a picture is nor
  what it is may be settled from a single frame.
- **`platform/content/PurposeTrainedLabeler.kt`** — the slot a model built for this job sits in,
  loaded from `assets/nsfw.tflite` through ML Kit's custom image labelling. Empty in this build, so
  `ModelIdentity.purposeTrained` stays false and the general labeller keeps judging as a declared
  proxy. `assets/README-content-model.md` says what to drop in, and why the covering loop is the
  one place a hosted vision model does not belong.

- **`data/source/BundledHostLists.kt`**, **`HostSet.kt`**, **`platform/network/AdultHostKeywords.kt`**
  — real category coverage for the adult and violence categories. The list is StevenBlack's porn
  extension (MIT and CC BY 4.0 sources, attributed in `assets/blocklists/NOTICE.txt`); the keyword
  rules are ported from BlockNSFW. Measured against UT1's adult category crossed with the Tranco
  top million, popular adult sites caught went from 0.5% to about 72%. `HostSet` exists because
  testing every rule per lookup costs milliseconds at this size on the DNS path. Entries that
  over-reach (CDNs, general hosts, sites that are not adult) are in `NEVER_BLOCK`, and
  `BundledHostListsTest` holds the popular and system hosts that must never be refused. Never
  bundle a list without checking it against the most-visited sites first: BlockNSFW's larger
  list was measured refusing 110 of the top 10k, including a CDN and a country's government.

## Rules this codebase follows

These are not style preferences. Each one exists because breaking it produces a parental control
that lies to a parent.

1. **Never show a zero you were not allowed to measure.** A missing grant produces `Unavailable`
   with a reason, and the UI says so. A dashboard reading `0m` because it never had usage access is
   the most dangerous thing this app could display.
2. **Distinguish blocked from detected.** `EventAction.BLOCKED` and `EventAction.NOT_ENFORCEABLE`
   are different, and the event log shows the difference.
3. **Never persist content.** Message bodies, screen text, prompts and full URLs are scored in
   memory and dropped. What is written is a length, a verdict and the categories that matched.
   `SafetyEventRepository.record` is the only write path into the log and this rule lives on it.
4. **State the limits on the screen that claims the capability.** Every protection screen carries a
   "what to expect" section naming what it cannot do.
5. **A window-manager overlay is not an Activity.** `ChildSafetyTheme` must never assume its context
   is one; the block screen, the surface notice and the blur layer all run outside an Activity.
6. **Never read DataStore inside a hot callback.** Request interception and the DNS pump cache their
   settings and observe changes; a preference read per subresource is a disk read per image.
7. **One socket per DNS lookup, never one for the tunnel.** Answers arrive in whatever order the
   network delivers them, so a shared socket can return query A's answer to query B. The asking
   app drops the mismatched transaction and waits for an answer already spent, which is a page
   that never loads. `ChildSafetyVpnService.forward` opens its own socket per query and the writes
   back into the tunnel are serialised behind a mutex.
8. **Only read a text field, never the body of a page.** `SearchTermGuard` is handed the address
   bar, editable fields and search-result titles. Matching a restricted word anywhere on screen
   would close a news article, and a protection that does that gets switched off.
9. **A protection added by an update starts on.** Stored feature sets are unioned with anything
   the parent has never been offered, tracked by `known_features`. Without it, shipping a
   protection means nobody who has ever touched a toggle is protected by it.
10. **Dimming is not covering.** What survives a translucent wash is `(1 - alpha)` of the original
    contrast, and a silhouette reads at a few percent of it, so a cover at 85% still shows the
    photograph underneath. `VideoBlurContent` therefore starts at 0.955 and the slider moves within
    that range. `FLAG_BLUR_BEHIND` is requested from Android 12 and is always a bonus: the system
    withdraws cross-window blur without telling the app, and most hardware never had it — the
    Pixel 4 this was driven on reports `mBlurEnabled=false`, so the wash was the whole cover there.
    A wash is the fallback and never the mechanism. What destroys detail is a blurred copy of the
    picture drawn in its place by `ImageBlur.blurPreview`, in a window the system lets be opaque
    (rule 15), and nothing is tinted on top of it: over a real blur a scrim hides nothing the blur
    has not already hidden, and every bit of darkening added was what made a cover read as a panel
    laid over the page instead of the picture being out of focus. The wash survives only for a
    cover with no picture to draw and for the capped fallback, where something really does show
    through.
11. **A model that was not built for the job must say so.** `ModelIdentity.purposeTrained` is false
    for the bundled labeller and everything downstream keys off it. No screen may present a verdict
    from a non-purpose-trained model as a safety determination. `SubjectCategory.APPEARS_MALE` is
    named for what is measured, because a model on a phone is not entitled to state a stranger's
    gender as fact.
12. **Fail towards covering, never towards exposure.** If a parent selects a subject category and
    nothing on the device can tell the categories apart, cover everyone. Covering none would deliver
    no protection behind a switch that still reads as on. Same rule in `applyCover`: an unsafe frame
    with no detected subject falls back to the whole screen.
13. **Measure the whole exposure window, not the convenient part.** `BlurLatency` reports frame
    arrival to cover raised, including the sampling gap, because quoting the classifier's time as
    though it were the time content was visible is the reporting mistake this feature invites.
14. **A full-screen overlay with nothing to tap must be `FLAG_NOT_TOUCHABLE`.** `FLAG_NOT_TOUCH_MODAL`
    passes through only what lands outside the window, and a window the size of the screen has no
    outside — so it swallows every touch on the device and nothing reaches the app underneath. The
    covers had this wrong and the phone appeared to freeze for as long as anything was covered: no
    scrolling, no leaving the browser. Only an overlay the child is meant to press, like the block
    screen, takes touches. See `BlockOverlayController.coverFlags`.
15. **Only a trusted overlay can be both opaque and pass-through, so covers are added through the
    accessibility service.** From Android 12 the system caps the opacity of any `FLAG_NOT_TOUCHABLE`
    overlay at `maximum_obscuring_opacity_for_touch`, 0.8 by default, so a person can always see
    what they are about to touch. It is applied to the window whatever the app asks for and
    `dumpsys window` shows it as an `alpha=0.8` nobody set. A cover at 0.8 composites a fifth of the
    original picture at full sharpness over the blur: a double exposure in which the photograph is
    still perfectly readable, reported as "it doesn't look like a blur, it's a shaded overlay".

    The cap applies only to *untrusted* windows, and a window added through an `AccessibilityService`
    is trusted. So the covers are added with that service's window manager as
    `TYPE_ACCESSIBILITY_OVERLAY`, which is opaque and invisible to touch at once, and the page under
    them scrolls exactly as before. The intermediate answer — making each picture-sized window
    *touchable*, which also escapes the cap — is the one thing this must not go back to: it is fine
    over one picture and unusable over a screen full of them, which is the screen this feature
    exists for. Without the accessibility grant the fallback gives up opacity, never the scroll —
    which takes rule 21 as well as this one, because the cap is applied per window and the limit is
    checked over the stack. See `BlockOverlayController.coverSurface` and the KDoc on `PatchCover`.
16. **Coordinates measured in one space must be drawn in that space.** The covers are fractions of a
    screen capture, and a capture is of the whole display. Two things broke that. An overlay window
    is laid out inside the system bars by default, so its origin is below the status bar and it is
    shorter than the display — every fraction resolved tens of pixels too high, and covers sat
    beside their pictures. And an `ImageReader` plane is padded out to a hardware stride, so the
    frame is wider than the screen unless the padding is cut off: the extra columns are transparent,
    which skews every horizontal fraction *and* bleeds transparency into any blur sampled near the
    right edge. See `Image.toBitmap` and `LayoutParams.coverWholeDisplay`.
17. **Never classify your own output.** Screen capture mirrors everything on the display, this app's
    overlays included. Anything that decides whether a cover stays up must take its evidence from
    screen the app is not covering, because the covered part shows the cover. Breaking this does not
    fail loudly: the cover scores like content, holds itself up, and the feature reads as a blur that
    will not go away. `ScreenRegions.DETAIL_FLOOR` and `SceneSignature`'s ignore set are where this
    lives.
18. **A setting with no screen is not a setting.** `ProtectionLevel` and `ValuesCategory` were read by
    enforcement and settable by nobody, and the category the bundled model detects best shipped off
    by default, so the feature covered nothing while reading as on. A preference that changes
    behaviour needs a control, and a default that depends on a model's actual capability has to be
    chosen against `DetectionQuality`, not against how the category sounds.
19. **The child-facing surface says what happened, never why the model thought so.** Scores, labels
    and measurements belong in the event log, where the parent reads them. Reciting them over the
    content teaches a child exactly what to avoid next time.
20. **A scroll moves the covers; it does not invalidate them.** The covering loop used to ask one
    question — did the screen change — and answer it by discarding every cover. On a feed that is
    the worst available answer: scrolling changes the screen on every frame, so every cover was
    thrown away and re-derived several times a second, which is what a parent reports as the covers
    juggling, and in the frame between discarding and re-deriving the picture was on display with
    nothing over it. A scroll is the same layout at a different offset. `RowProfile` measures that
    offset directly by aligning one frame's row averages against the last, `CoverPolicy` moves the
    covers by it, and only a frame that lines up nowhere is treated as a new layout. What it takes
    for that measurement to be trustworthy enough to act on ten times a second is rule 23. Where the
    offset is not believable the behaviour falls back exactly to what it was, so this is an
    addition rather than a re-tuning. Judging and following are also decoupled in time:
    `TARGET_FPS` is how often the screen is classified and `TRACK_FPS` is how often the covers are
    allowed to follow it, because a feed under a moving finger travels the height of a thumbnail
    between two judgements.
21. **Covers are decided as overlapping rectangles and drawn as ones that do not overlap.** Android
    caps the opacity of an untrusted pass-through window, but it applies the limit to the *stack*:
    two capped windows over the same pixel obscure 0.96 of it, over the limit, and every touch
    landing there is dropped outright. Covers are grown by a margin so their edges meet, so on a
    two-column grid that is a seam a few pixels wide running down the middle of the screen — which
    is where a thumb scrolls. `ScreenRegions.withoutOverlaps` trims it, and only ever gives up
    ground a neighbouring cover already holds, so the covering is unchanged.
22. **A protection must not stop because the app was swiped out of recents.** Every enforcement
    service declares `stopWithTask="false"` rather than relying on the platform default. Video
    blurring is the one that cannot recover by itself: Android issues screen capture consent one
    session at a time and ties it to the process that asked, so a process a manufacturer's build
    kills cannot replay the consent it held. `SafetyPreferences.captureRequested` records that a
    parent started capture, `MonitoringService.checkCaptureAlive` compares that against whether it
    is running, and the difference raises the notification that offers to start it again. Silence
    is the failure mode being designed against: before this, capture died and nothing said so.

23. **Where a picture is, and what it is, are both measured across frames rather than read off
    one.** Three separate things went wrong here and each produced a different complaint about the
    same feature. *Position:* the covers are excluded from the motion estimate, so a row crossing
    one is averaged over what is left of it — and what is left of it changes the instant a cover
    moves. Comparing that against the same row averaged over its whole width measures the cover
    rather than the page, so every raise, lift and nudge fed a step change back into the estimate
    and the covers moved in response to their own movement. `RowProfile` records which column bands
    produced each row and refuses to compare rows built from different ones. *Confidence:* a page
    of repeating cards aligns about as well at several offsets, and taking the winner of a field of
    equals is drawing a displacement out of the noise floor — ten times a second, that is a random
    walk. `RowProfile.isDistinct` makes the winner beat every offset that is not its own shoulder,
    and `CoverPolicy.MAX_UNCONFIRMED_DRIFT` is the backstop: nothing re-confirms a cover while it
    stands, so one carried a screen's worth since anything saw its picture comes down and earns its
    place again. *Identity:* a layout change used to be the mean difference across the frame, which
    a video playing in a third of the screen clears on its own — so a scene cut was read as a
    navigation and every cover on the page was thrown away and rebuilt. Requiring most of the
    *rows* to move fixes that and introduces the opposite failure, because a row is an average
    across the whole width: two screens from one app share a palette and differ in where things
    sit, which averaging away the horizontal axis erases completely. Real navigations stopped
    being recognised, and covers judged on one page stood over the next one until a hold timer
    released them one at a time. A layout change is therefore decided on the two-dimensional grid
    — `SceneSignature.isNewLayoutVersus` — where a navigation and a playing video are far apart:
    a navigation replaces nearly every cell and a player changes only its own. The grid cannot
    tell a new screen from a *moved* one, since a scroll of one cell's height also changes every
    cell, so a measured displacement outranks it; and a window change reported by the
    accessibility service outranks everything, because that is not an inference about pixels at
    all. See rule 25.
24. **A verdict is evidence about a picture, not a fact about a frame.** The same thumbnail scored
    twice does not score twice the same: a label appears at 0.36 and is gone at 0.34, the crop
    lands two pixels differently, the fused score moves by a tenth. A threshold applied to one
    reading is a coin flipped at the sampling rate, and the cover it controls blinks over exactly
    the content it is meant to hide. `VerdictStability` averages a score into what the last few
    frames said about the same picture and reads it through two thresholds — the parent's chosen
    strictness to cover, a fraction of it to uncover — so the gap only ever makes a cover harder to
    remove. It also remembers a picture across the examination that lifts its cover, because a
    covered picture cannot be classified and would otherwise be met as though for the first time.
    The other half is resolution: `CAPTURE_SCALE` is what every verdict is taken at, and a crop
    upscaled to reach the model's input is an interpolated picture the model cannot read
    consistently.

25. **Screen capture cannot see an app, so something else has to say when one changes.** A cover
    is judged in a window, and the moment a different window is in front it is a rectangle over
    content nobody looked at. From the capture's side that moment is indistinguishable from a cut
    in a video — both are "the pixels are different now" — and the covers were left standing over
    the next page and over the app switcher for as long as their hold timers ran.
    `SafetyAccessibilityService` already knows which app's window is in front, so it publishes
    that and nothing else through `ScreenSignalStore.noteForegroundWindow`: a package name and a
    count, no window title, no class, no content, and no tree walk, so it is not on the throttle
    the rest of that service's work is on. Two properties are load-bearing. Only a genuine change
    of package counts, because window events repeat constantly inside one app. And this app's own
    package never reaches it — the covers this app draws *are* windows, and rule 17 applies to
    them here as much as to their pixels: a cover raising itself must not be able to report a
    window change that takes it back down. The grant is optional, and without it the grid in rule
    23 does the work alone.
26. **A cover's blurred copy belongs to a picture, not to a rectangle.** The copy each cover draws
    is taken on the frame its picture was last actually seen, and kept while the cover stands,
    because the capture shows the cover in that place and re-sampling would blur the blur until
    the patch was a flat panel. Deciding whether to keep it from geometry alone puts the wrong
    photograph on screen: a page turns, a fresh cover goes up over the new page's first thumbnail,
    it lands where the old cover was, and the copy that still "fits" is a picture of the page that
    is gone. `CoverPolicy.mayReusePreview` is the rule — a cover raised on this frame has no
    history to keep — and it is expressed there, away from the service, so it can be tested.

27. **Where the covers are thickest the pixels cannot see a scroll, so the app's own report is
    believed first.** The motion estimate is taken from screen this app is not covering (rule 17),
    and on an image search with its pictures covered almost nothing is left: driven on a Pixel 4,
    five flings produced no measured displacement at all, the covers stood still while the page
    slid out from under them, and at the end of each fling the distance travelled looked like a new
    page and every cover came down for half a second. The app doing the scrolling knows how far it
    went and says so in `TYPE_VIEW_SCROLLED` — a delta from RecyclerView and Chrome's content view,
    an absolute offset from Chrome's WebView, both in screen pixels, and for the same scroll in
    Chrome, so they are deduplicated rather than added. `ScrollLedger` keeps the running total;
    `ScreenAnalysisService.motionOf` prefers it to the pixels, stops the grid calling an actively
    scrolling page a navigation, and reads it at each frame's own capture time. A reported distance
    does not count towards `MAX_UNCONFIRMED_DRIFT`, which exists for estimates. The last step before
    drawing moves every cover on by however far the page went since its frame was composed, so a
    cover is placed where its picture is rather than where it was. The service's notification
    timeout is zero because Android keeps only the last event inside the timeout, and a scroll
    report dropped is distance lost. Only the numbers on the event are read.
28. **A frame the loop is too busy for is held, never dropped, and a cover's own edges are not
    evidence.** A virtual display only produces a frame when the screen changes, so on a still
    screen the last frame delivered is the last there will be. The frame produced by taking covers
    down — the one that shows what they were hiding — arrived while the loop was busy and was
    thrown away, and the pictures stayed uncovered for twelve seconds until something else moved;
    see `pendingImage`. Separately, a grid cell a cover takes more than a sliver of is left out of
    the layout comparison (`SceneSignature.of`), and for a moment after the covers change their old
    positions are too (`settlingRegions`), because the capture shows them a frame or two late. Both
    were measured on the device as every cover dropping on a page nobody was touching.
29. **A still page keeps its covers, and a body label is not exposure.** A page seen to be still
    with most of it visible re-confirms every cover on it (`CoverPolicy.next`); the hold timer
    used to lift each one after twenty seconds on a page nobody touched, which reads as the blur
    changing by itself. The timer still runs where motion cannot be measured and on a mostly
    covered screen. Separately, "Skin", "Flesh" and "Muscle" describe a body, not a state of
    dress — the labeller reports them on babies, fur and landscapes — so they count in full only
    beside a clothing label (`SafetyLabelRisk.Weighted.bodyOnly`), and a picture clearly of
    something else cancels them. The cost is stated: a swimsuit photograph the labeller does not
    call "Swimwear" is no longer covered on bare skin alone.

30. **The tunnel's posture comes from what the parent asked for, never from memory.** The tunnel held
    its mode in a field that starts as filtering in every new process, so a service Android
    restarted after killing it — or one reclaiming the slot from another VPN — came back filtering
    while the parent's pause was still saved as on. The internet quietly returned and the screen
    still said paused. `ChildSafetyVpnService.onStartCommand` reads the saved pause and filtering
    posture on every start and settles into `TunnelMode.restingFor`, and the monitoring loop puts a
    pause back when no restart happens at all. A start the posture has no tunnel for calls
    `startForeground` before standing down, because a foreground-service start that never does is
    a crash.

31. **A picture nobody could look at is never shown.** A browser's private or incognito tab marks
    its window FLAG_SECURE, and Android blacks it out of every capture — MediaProjection and the
    accessibility screenshot alike — so no picture in it can be judged. Driven on a realme RMX3870,
    Android 16: `dumpsys window` shows `fl=… SECURE`, a screenshot is solid black, and the capture
    loop had nothing to cover. Covering only what fails would cover nothing behind a switch that
    reads as on, so the parent chooses (`PrivateTabAction`, default cover every picture) between
    covering every picture and not allowing private tabs. Recognising one takes two signals
    (`PrivateTabTracker`): the frame is black outside this app's own covers (`SecureFrame`), *and*
    the app that owns the active window is a browser — a bank or a streaming app is black too, and
    a dark-theme page is not. The black test leaves out every place a private-tab cover has been
    since shortly before the frame was captured, because the capture shows covers late and a held
    frame is older still (rules 17 and 28); it enters at 97% black and leaves only below 90% on
    evidence that holds for 400 ms, because each looser version was measured dropping every cover
    — once mid-scroll, once on a page nobody touched. `PrivateTabGuard` then works from the
    accessibility tree, which a secure window does not hide: the whole page is covered at once,
    `ImageRegions` (ported from `blurtest2`) narrows it to one opaque patch per photo, patches follow
    the browser's reported scroll, and a walk that cannot account for every photo leaves the page
    covered. It runs on its own thread, because the service's own tree walks held a cover up by 370
    ms on the main one. A browser left while private is covered again the moment it returns, not
    when the capture next looks (measured 50-60 ms instead of 0.6-1.5 s). The active window is read
    again 250 ms after a window change, because the event can arrive while the old window is still
    active. The capture loop stands down while a private tab holds the screen. Only the browser's
    package and a count reach the event log (rule 3).

## Testing

Unit tests cover the pure logic: the protection catalog invariants, the allowance arithmetic, the ad
blocklist (including the hosts that must *not* be refused, so YouTube still plays), the AI prompt
scorer's thresholds, host filtering, DNS and IPv4 parsing, and the image classifier's fusion rule.

Anything needing a device is verified by running it. `./gradlew installDebug`, then grant access
from the Permissions screen.

## Conventions

- Package root: `com.example.childsafetyimpl`
- Routes in `navigation/Routes.kt`; never assemble a path string at a call site
- Curated package lists in `data/source/KnownPackages.kt`, and every package there must also appear
  in the manifest's `<queries>` block or Android 11+ visibility filtering hides it
- Screen signatures in `platform/accessibility/SurfaceSignatures.kt`, kept as data so the fragility
  of matching another app's private view ids stays measurable
- Ad and tracker hosts in `platform/network/AdBlockList.kt`, deliberately narrow
