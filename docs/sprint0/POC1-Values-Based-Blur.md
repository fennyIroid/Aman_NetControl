# POC 1 — Values-Based Blur

**Sprint 0 Deliverable 3 (part 1 of 2)** · Android and iOS
**Status:** engineering complete, device measurement pending
**Prepared by:** iRoid Solutions

---

## 1. What this POC had to answer

Validate the technical approach for detecting content against the agreed values categories and
applying a blur or cover effect; assess Android and iOS differences, privacy considerations and
performance impact; and define where the approach can and cannot be relied upon in production.

The client subsequently clarified the target experience with a video reference:

> applying the appropriate blur/cover effect to images or videos, with the ability to control the
> target category, for example male or female content.

That clarification changed the POC materially and the work below reflects it. It asks for two
things the original implementation did not do: **selective covering** of subjects rather than the
whole screen, and **targeting by who is in the picture** rather than by what the scene contains.

## 2. Headline result

**Verdict: Go with limitation.**

The pipeline works. Frames can be captured, judged on the device and covered, and the selective form
matches the effect the client's reference shows. The approach is viable and should proceed.
Section 2a records what changed once it was driven on a real device, and covering the individual
failing pictures — not the whole screen — is now the default.

Three limitations are firm and must shape both the design and what the product claims:

1. **The classifier is a stand-in.** Google ships no on-device explicit-content model. What is
   bundled today is a general image labeller used as a proxy. It is good enough to prove the
   pipeline and is **not** good enough to carry a safety claim. A purpose-trained model is required
   before launch. This is a procurement and training task, not an engineering blocker — the slot
   for it is built.
2. **Gender targeting cannot be delivered reliably.** See section 5. It is buildable as best-effort
   and should not be presented to a parent as dependable.
3. **iOS cannot do this at all outside our own app.** See section 6. This is not a gap to be closed
   later; it is a platform prohibition, and the design must not imply otherwise.

## 2a. What device testing changed

The POC above was written from the design. Driving it on a Pixel 4 against live Google Image results
changed four things, and each change came from a defect that only appears on a real screen.

**The unit of judgement was wrong.** The pipeline classified the whole captured frame and covered the
whole screen when it failed. On the screens people actually look at — a grid of search results — that
does not work in either direction. One unsafe thumbnail among twelve does not move a whole-frame
score, so nothing was covered; and when something did fail, the entire screen went, including the
eleven pictures that were fine and the search box the child needs to get out. The frame is now
segmented into picture-shaped regions and each one is classified on its own. Measured on the device:
**4–6 pictures found per screen**, covering 1–2 of them and leaving the rest readable.

**A browser screen scored itself safe.** `Screenshot`, `Web page` and `Font` were weighted as evidence
of safety, and they are true of every frame a screen capture produces. Scoring anything in a browser
subtracted a large constant from the verdict. Those labels now carry no weight in either direction.

**The cover held itself up.** Everything this app draws is mirrored back into its own capture, so the
classifier was scoring the blur it had just raised. Lifting was decided by re-classifying ground the
cover was standing on, which cannot come back clean. Covers are now held and released by a scene
fingerprint measured from the parts of the screen that are *not* covered, with a staggered timed
re-examination as the backstop — one cover at a time, never all of them, so re-examining costs one
picture visible for one frame rather than the whole page.

**The one category the model can detect was switched off and unreachable.** `SUGGESTIVE_IMAGERY` is the
category the bundled labeller handles with real confidence (`WORKABLE`); `EXPLICIT_IMAGERY` is the one
it can only infer (`PROXY_ONLY`). The default enabled only the second, and no screen offered the
first, so a swimwear search covered nothing. This is the single finding that best explains the
reported symptom. Suggestive imagery is now on by default, and the category, sensitivity and mode
controls are on the screen.

### Measured after the change, on a Pixel 4, Chrome, live Google Images

| Case | Result |
|---|---|
| `swimwear beach`, images | 6 pictures found, 2–5 failed and covered, worst score 0.76–0.84 |
| `beach vacation`, images | 1–2 pictures found, none covered, worst score 0.27–0.34 |
| `actress`, images, cover-people mode | 4 people found and covered, held without flicker |
| Scan cost | ~135 ms per scan, scans run on screen movement rather than every frame |
| Session stability | No crash across repeated browse-and-scroll runs |

The second row is the one worth reading twice: a beach search is **not** covered, which is the
behaviour a filter has to have if a parent is going to leave it switched on.

### What is still limited

Recall is uneven picture to picture. On the same screen one swimwear photograph scores 0.84 and
another scores 0.29, because the labeller's output varies with crop and pose. This is the
`PROXY_ONLY` limitation in section 1, now measured rather than predicted, and it is the strongest
argument for the purpose-trained model: the mechanism around it is no longer the constraint.

Cover placement follows the page rather than leading it. See section 3.5: during a scroll a cover
sits about a tenth of a second behind its picture, and a hard fling moves a feed further in one
sampling interval than anything on it can be matched across, at which point the covers come down
and are worked out again on the new screen.

### The client's reference, and what it tells us

The reference shared during the sprint is **HaramBlur**, a browser extension. Two things about it
shape the recommendation. It blurs **per image**, leaving the page usable — which is what the
redesign above now does. And its criterion is **a person of a chosen appearance**, not a safety
score: in the reference video a search for "actress" blurs fully-clothed portraits. That is a values
filter, not an NSFW filter, and it is a materially easier problem, because face detection is a
purpose-trained detector that works while an on-device NSFW classifier does not exist.

`BlurMode.SUBJECTS` was therefore changed to stop routing through the safety classifier. It now
covers every detected person whatever they are doing, which is what a family choosing that setting
means. **This is the mode that behaves closest to the reference today, and it is the more reliable
of the two paths on current Android.** Gender narrowing within it remains subject to section 5.

## 2b. Two defects found by driving it, and what they were

Both were found on a device and neither was visible in any unit test, which is worth recording
because they are the two shapes of defect this feature keeps producing.

**The covers juggled during a scroll.** The loop asked whether the screen had changed and answered
by discarding every cover. A scroll changes the screen on every frame, so on a feed every cover was
thrown away and re-derived several times a second — and in the frame between the two, the picture
was on display with nothing over it. The fix is to measure *how far* the page moved rather than
*whether* it moved, and to move the covers by that amount; a frame that lines up nowhere is still
treated as a new layout. See `RowProfile` and rule 20.

**Without the accessibility grant, a strip down the middle of the screen silently ate touches.**
Android caps the opacity of an untrusted pass-through window at 0.8, and `dumpsys` duly showed
every cover at `alpha=0.8`. But the limit is checked over the *stack*, not per window: two capped
covers over the same pixel obscure `1 - 0.2 x 0.2` = 0.96, over the limit, and the touch is dropped
outright rather than the cover being dimmed further. Covers are grown by a margin so their edges
meet, so on a two-column grid the overlap is a seam a few pixels wide running down the centre —
exactly where a thumb scrolls. The fallback therefore did not "give up opacity but keep the
scroll"; it lost both. `ScreenRegions.withoutOverlaps` trims the seam, giving up only ground a
neighbouring cover already holds, so the covering is unchanged. See rule 21.

## 3. How it works

```
screen ──► MediaProjection ──► sample at 2 fps ──► on-device classifier ──► verdict
                                                              │
                                    ┌─────────────────────────┴──────────────────┐
                                    ▼                                            ▼
                         whole-screen cover                          face detection ──► region cover
```

Everything runs on the device. No frame, crop or thumbnail is transmitted or written to storage.
What survives a frame is a score, a category set and a duration — which is what the event log holds
and all it holds.

### 3.1 Built during this sprint

- **Pluggable classifier slot** (`ContentSafetyModel`). A purpose-trained model drops in and changes
  the verdicts and nothing else around it.
- **Model provenance** (`ModelIdentity`). Every verdict can state what judged it and whether that
  thing was purpose-trained. No screen may present a non-purpose-trained verdict as a safety
  determination, and the flag is what enforces it.
- **Selective region covering** (`SubjectDetector`, `RegionBlurContent`). Face detection produces
  bounding boxes, expanded geometrically to head-and-torso; those regions are covered and the rest
  of the screen is left readable.
- **Category and strictness controls** (`ValuesCategory`, `ProtectionLevel`). Blur On/Off, three
  strictness levels, and per-category switches — the configuration surface §24A asks for.
- **Exposure measurement** (`BlurLatency`). See section 4; this is the number the POC turns on.

### 3.2 Cover quality

The covering maths is inherited from the existing implementation and is worth restating because it
is the difference between a cover and a tint. What survives a translucent wash is `(1 − alpha)` of
the original contrast, and the eye reads a silhouette at a few percent of it. At 85% opacity a
photograph is still legible through the cover. The cover therefore starts at **0.955** and the
parent's strength control moves within that range — every setting available is still a cover.

Region covers use lightly rounded edges rather than hard rectangles: a crisp border draws the eye to
what is behind it and reads as censorship, where a soft edge reads as the picture not resolving
there.

### 3.3 Why a cover is a real blur and not a wash — and what Android charges for it

Testing on the device produced the client's sharpest piece of feedback so far: *"it does not even
look like a blur, it's just a shaded overlay."* That was accurate, and there were three independent
causes. All three are fixed; the third is a platform constraint worth the client understanding,
because it shapes what the feature can be on Android.

**A wash is all most Android hardware ever had.** Cross-window blur — the system blurring the live
picture behind a window — arrived in Android 12, and it is optional for the manufacturer. The
Pixel 4 used for this testing reports `mBlurEnabled=false`: it has never supported it. On that class
of device, and on everything before Android 12, the only blur available is one the app computes
itself. The app now does: it samples the picture, blurs it in software, and draws the blurred copy
back in place. The blur is expressed as *cells across the picture* rather than as a divisor of the
capture resolution, so a picture is reduced to the same recognisability on any phone.

**The capture frame was wider than the screen.** A capture plane's rows are padded to a hardware
stride, and the padding was never cut off. Every horizontal position was therefore a few percent
out, and the transparent padding bled into the blur of anything near the right edge.

**And an untrusted overlay that lets you touch through it is not allowed to be opaque.** Since
Android 12 the system caps the opacity of any pass-through overlay at 0.8, so that a person can
always see what they are about to touch. It is imposed on the window whatever the app asks for, and
it showed up in `dumpsys window` as an `alpha=0.8` nothing in the app had set. A cover at 0.8
composites a fifth of the original photograph, at full sharpness, over the blur — a double exposure
in which the picture is still perfectly readable. That is exactly what "a shaded overlay" was
describing.

The cap applies only to *untrusted* windows. A window added through an accessibility service is
trusted, because the system already depends on such services to draw over other apps for the people
who need them, so a cover added that way is opaque **and** invisible to touch at the same time. That
is the combination the feature needs and the only way to get it: the page underneath scrolls and
taps exactly as it did before anything was covered, and the cover is a genuine cover.

The intermediate answer, tried and rejected, was to give each picture-sized window its own touches —
which also escapes the cap, because a window that takes touches is not obscuring anything. It is
fine over a single picture and unusable over a screen full of them, which is precisely the screen
this feature exists for. The client's reaction to it was immediate and correct: a page you cannot
scroll is not a product. Where the accessibility permission is absent the cover therefore gives up
opacity rather than the scroll, is limited to 80%, and the protection screen says so.

### 3.4 What a cover draws, and what it deliberately does not

The reference the client gave for the target appearance was a browser extension that blurs images
inside the page. What it does to a thumbnail is worth stating precisely, because it is the whole
specification: it blurs the picture and leaves it otherwise alone. No tint, no border, no panel.

The app had been drawing a dark scrim over its blur. Once the window is opaque that scrim protects
nothing — the detail is already destroyed in the pixels, and no part of the original is reaching
the screen for a scrim to hide — while costing the one thing that makes a blur read as a blur,
which is the picture's own colour and composition. It is gone. A cover now draws the blurred copy
and nothing else, and a scrim survives only in the two places where something really does show
through: the capped fallback above, and a cover that has no picture to draw.

**The limit that remains.** A cover is placed from a frame up to half a second old, so during a
fast scroll it can sit briefly where the picture was rather than where it is now, and a cover left
behind by a scroll can linger for a frame before it is taken down. Closing that gap means tracking
the page's movement between frames rather than re-deriving the covers from each one, which is not
a change to the classifier or to the covering criteria but to the placement alone. Blurring a
picture genuinely in place, with no overlay at all and no lag of any kind, is available in exactly
one place on Android: a browser the product itself renders, which is what the app's own browser
already does.

### 3.5 Judging the screen and following it are two different jobs

Deciding *what* to cover means running a classifier over every picture-shaped region of the frame,
which costs 35–390 ms depending on how many regions there are. Deciding *where* the covers go costs
one average per row of the frame and a comparison against the row averages of the frame before —
about 30 ms — and has a much tighter deadline, because a feed under a moving finger travels the
height of a thumbnail between two judgements.

They therefore run at two rates. `TARGET_FPS` is two per second and is how often the screen is
judged. `TRACK_FPS` is ten per second and is how often the covers are allowed to follow it, and it
only runs while something is drawn. Measured on a Pixel 4 over a sustained scroll of Google Images:
of 30 judging passes, 15 carried their covers through a measured displacement instead of
discarding them, and no touch was dropped.

Two costs remain, and both are placement rather than protection:

- The covers follow up to one tracking interval behind, so about 100 ms, which at fling speed is
  roughly 150 px.
- While a judging pass is running, nothing follows the page. That is usually under 80 ms but
  reaches ~390 ms on a screen with five regions to classify.

Closing the second one means letting the cheap pass run while the expensive one is in flight, and
carrying the classifier's findings forward by however far the page moved while it was thinking.
That is the next step and it was deliberately not taken in this sprint: it is a concurrency change
to the loop that decides what a child can see, and it wants more soak time than a sprint review.

## 4. The measurement that matters

The number this feature should be judged on is not frames per second or milliseconds per
classification. It is **how long unsafe content is visible before it is covered**.

The exposure window has four parts and only one is the model:

```
content appears ──► next sample ──► bitmap ──► classify ──► cover raised
                └── sampling gap ┘ └──────── pipeline ────┘ └ compositor ┘
```

**The sampling gap dominates and is usually left out of such figures.** At two frames per second,
content appearing just after a sample waits up to 500 ms before anything looks at it. Even an
instantaneous classifier leaves a half-second worst case. Raising the sample rate closes it and
costs battery on a continuous capture that is already the most expensive thing the app does — that
is the real trade in this feature.

The implementation now measures the whole window and reports average **and** worst case, because an
average of 300 ms with a worst case of 320 ms is a very different product from the same average with
a worst case of 900 ms.

**Acceptance threshold: 1000 ms worst case.** Below roughly a second the cover is experienced as the
content being blocked. Above it, it is experienced as having seen the content and then had it taken
away — which is not a protection, and teaches a child that looking quickly works.

**Measured values — pending device runs.** Nothing is entered below; these must come from hardware.

| Device | Avg latency | Worst measured | True worst (incl. gap) | Classify share | Battery /hr | Subject fallback share |
|---|---|---|---|---|---|---|
| Pixel | — | — | — | — | — | — |
| Samsung | — | — | — | — | — | — |
| Xiaomi/Transsion | — | — | — | — | — | — |

One honesty note on how to read these once collected: the clock starts when a frame **arrives from
the capture pipeline**, not when the content was first drawn. Android does not expose the latter.
True exposure is the measured window plus an unmeasurable compositor and mirroring delay, typically
one to two display frames.

## 5. Gender targeting — the honest answer

The client asked for control over "male or female content". Buildable, with real caveats.

**Google ships no on-device gender classifier.** ML Kit's face API returns geometry — bounding
boxes, landmarks, head rotation, eyes open, smiling — and no gender. Delivering the category at all
requires bundling a separately trained network. The slot for one exists (`GenderModel`); nothing is
bound to it today, deliberately.

**Accuracy is worst in exactly the conditions this product operates in.** Published figures come
from curated frontal-portrait datasets. This pipeline sees video frames: motion blur, side profiles,
partial faces, small faces, poor light. Accuracy is materially lower there. The published bias is
also well documented and runs the wrong way — error rates are highest for darker-skinned and female
faces. And head covering, common in the launch market and correlating with precisely the households
most likely to enable this feature, degrades it further.

**What we built instead, and recommend leading with.** The reliable control is **"cover anyone"**,
which needs only face detection. Face detection works well across skin tones, head coverings and
ages, and it has no opinion about who anyone is.

Gender targeting ships as a clearly-labelled best-effort refinement. The categories are named
`APPEARS_MALE` and `APPEARS_FEMALE` in the code, not `MALE` and `FEMALE` — a model on a phone is not
entitled to state a stranger's gender as fact, and the naming is a product commitment rather than a
style choice.

**The fail-safe rule.** If a parent selects a gender category and no model is bound, the system
covers **every** person rather than none. A parent who asked for less exposure gets less exposure;
the alternative silently delivers nothing behind a switch that still reads as on. This is tested.

**Recommendation.** Lead the UI with "cover anyone". Offer gender targeting as a secondary option
with its limitations stated on the screen that offers it. If the client wants it as a headline
feature, that needs a dedicated model evaluation — including a MENA-representative test set with
head coverings — scheduled as its own piece of work with its own accuracy acceptance criteria.

## 6. Platform differences

### Android

Works, within MediaProjection's constraints — and those constraints are the main limitation:

- **Consent is per session and cannot be held.** From Android 14 the parent re-grants after every
  reboot, call interruption, or switch to another capture app. **"Always-on video blur" is not
  achievable from a Play Store app.** The product's answer is to notice the session ending, tell
  the parent, and offer a one-tap route back — which is implemented.
- **Capture is always visible.** Android 15 defaults to single-app sharing and shows a prominent
  status-bar chip the user can tap to stop. Android 15 QPR1 and later make it more prominent still.
  The child can always see that capture is running and can always stop it.
- **FLAG_SECURE windows mirror as pure black.** Banking apps and some video apps mark their windows
  secure. These frames are counted separately rather than scored as "nothing unsafe here", which
  they are not.

### iOS

**Values-based Blur cannot be delivered inside third-party apps on iOS. There is no partial
version of this.**

iOS has no equivalent of MediaProjection available to third-party apps, no equivalent of an
accessibility service that can read another app's screen, and no mechanism for drawing over another
app. ReplayKit records the screen but delivers frames only to the recording app's own extension and
cannot be used to observe and overlay other apps continuously.

What is achievable on iOS:

| Capability | iOS |
|---|---|
| Blur inside Aman's own browser | **Yes** — full parity |
| Blur inside third-party apps (Safari, Instagram, TikTok) | **No** |
| Whole-app blocking of those apps | Yes, via Managed Settings shields |
| Web content filtering | Yes, via Managed Settings / a network extension |

The honest iOS story is whole-app control plus filtering inside Aman's own surfaces. **The Figma
must not show Values-based Blur as an available iOS capability.** This is already on the client's
own "do not design as confirmed" list — "universal real-time video blurring inside every third-party
app" — and Sprint 0 confirms that judgement was correct.

## 7. Privacy

| Question | Answer |
|---|---|
| Do frames leave the device? | No. All classification is local. |
| Are frames stored? | No. Read, scored, recycled. |
| What is retained? | A score, matched categories, a duration. |
| Are faces identified? | No. Detection returns bounding boxes; no recognition, no identity, no template. |
| Can a parent see what the child saw? | No, deliberately. The log holds verdicts, never content. |
| What does the child see? | That something is covered. Never the score, model or category — reciting those teaches a child exactly what to avoid next time. |

Any future model must preserve this. A face crop leaving the handset is a different product with
different disclosures, and no accuracy gain justifies it.

## 8. Recommended production direction

1. **Proceed on Android.** The pipeline is proven; the measurements will confirm the exposure window.
2. **Commission or license a purpose-trained content classifier.** This is the gating item for any
   safety claim. Budget for evaluation against a MENA-representative test set.
3. **Lead with "cover anyone".** Treat gender targeting as best-effort and label it as such.
4. **Keep whole-screen covering as the default** until the subject-fallback share is measured. If
   the detector misses subjects often, region mode is quietly degrading to whole-screen anyway.
5. **Design the session-ended state properly.** On Android this is a frequent, normal occurrence, not
   an error path, and it is where this feature will be won or lost in daily use.
6. **Mark Values-based Blur as Android-only in the Figma**, with the iOS alternative being whole-app
   control plus Aman's own browser.
7. **Upgrade region covering to segmentation** once the classifier is replaced. Geometric expansion
   from a face box is a reasonable approximation; a segmentation mask is visibly better and costs
   several times more per frame, which is only worth paying once the verdicts are trustworthy.
