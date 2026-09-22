# Store Policy and Entitlement Readiness

**Sprint 0 Deliverable 5** · Early readiness summary
**Prepared by:** iRoid Solutions

---

## Why this is the highest-risk item in the project

Every technical limitation in the other Sprint 0 documents is something we can design around. A
store rejection is not. It stops the product shipping regardless of how well it works, and it is
discovered late — after full development — unless it is worked deliberately from the start.

Aman depends on five of the most heavily scrutinised capabilities on Android and one gated
entitlement on iOS. None of these are unusual for a parental control product, and all of them are
approvable. But each carries a declaration that must be right, and two have live 2026 policy
changes attached.

**This document is a readiness assessment, not an approval.** Approval timelines belong to Google
and Apple and are explicitly outside the Sprint 0 completion criteria.

## Android — Google Play

### Summary

| Capability | Policy exposure | Assessment |
|---|---|---|
| AccessibilityService | **High** | Approvable; strictest review; 2026 enforcement tightened |
| QUERY_ALL_PACKAGES | Medium | Approvable — parental control is a declared accepted use |
| NotificationListenerService | **High** | Approvable but narrow; consider deferring |
| MediaProjection | Medium | Approvable; heavy UX constraints, not policy ones |
| VpnService | Medium | Approvable — must be core functionality, which it is |
| Device Admin | Low | Limited capability, low scrutiny |
| Foreground services | Low | Declarations already correct |

### AccessibilityService — the one to get right

**What we use it for:** Shorts and Reels control, Status and Stories control, reading the address
bar and search fields of other browsers, AI conversation surfaces, and the VPN settings screen.

**Policy position.** Google's Accessibility API policy permits non-accessibility use where the
purpose is prominently disclosed and the user consents. Parental control is an accepted category.
**We cannot claim `isAccessibilityTool="true"`** — Google restricts that flag to screen readers,
switch access, voice input and Braille tools, and explicitly names monitoring and parental control
apps as ineligible. Our manifest correctly does not claim it.

**2026 change.** Google tightened AccessibilityService review with an enforcement date of
28 January 2026, which has now passed. Submissions are subject to the stricter process.

**What is already in place:**
- Service scoped to the narrowest event types that work (`typeWindowStateChanged`,
  `typeWindowContentChanged`)
- `canRetrieveWindowContent` declared, which is what makes the service policy-sensitive
- A written accessibility purpose string
- No `isAccessibilityTool` claim

**What is still needed:**
- A prominent in-app disclosure before the grant is requested, with explicit consent, separate from
  the Play listing. The design scope's §1C permission-introduction screens are the right place, and
  they must be designed to satisfy this rather than only to reassure.
- A Play Console declaration describing exactly which features depend on the service
- A demo video for review showing the parental-control use in context

**Android 17 Advanced Protection Mode** revokes this grant when the user enables that mode, and
blocks re-granting. This is a runtime behaviour, not a policy matter, but it affects the same
features and is covered in the POC 2 and iOS/Android scope documents.

### NotificationListenerService — recommend deferring

**What we use it for:** message safety signals — frequency, timing, and risk scoring of notification
previews. No message body is stored; only a length, a verdict and matched categories.

**Assessment.** This is the narrowest policy allowance of the five and the hardest to justify,
because the data is highly sensitive and the feature is a secondary signal rather than a core
protection. It also delivers less than it appears to: notification previews are often suppressed or
truncated, group chats are unclear, outgoing messages are invisible entirely, and in-game chat
(Roblox) never appears in notifications at all.

**Recommendation: leave it out of the first submission.** Ship the core protections, establish the
listing, then add it in a later release with its own declaration. Shipping it in v1 puts the entire
app's review at risk for a feature whose real-world coverage is partial at best. This aligns with
the client's own instruction not to present complete chat monitoring as a confirmed capability.

### QUERY_ALL_PACKAGES

Restricted permission requiring a Play Console declaration. Parental control is an accepted use.
The manifest already carries a `<queries>` block as the narrower fallback, which is what should be
emphasised in the declaration: we use the broad permission for completeness of the installed-app
list and clone detection, and degrade gracefully without it.

### MediaProjection

Policy exposure is low — the constraints are UX constraints, covered in the POC 1 results. The
declaration must state that capture is used for on-device content classification only and that no
frame leaves the device or is stored. This is true and is enforced in the implementation.

### VpnService

Play requires that VPN functionality be the app's core purpose or clearly disclosed, and prohibits
using it to collect user data or redirect traffic for profit. Our use is content filtering, the
tunnel carries DNS only, and no traffic is logged or transmitted. This is a clean fit. The Data
Safety form must state that DNS queries are processed on the device and not transmitted to us.

### Data Safety form

Needs to be prepared carefully and will be scrutinised given the permission set. The honest position
is unusually strong and should be stated plainly: **no message content, screen content, prompts or
full URLs are ever stored or transmitted.** What is retained is lengths, verdicts and matched
categories. This is a rule enforced at the single write path into the event log, not a policy
statement.

## iOS — Apple

### Family Controls entitlement

**`com.apple.developer.family-controls` is required and must be requested from Apple.**

Key facts:
- The **development** entitlement works locally without approval. **Distribution** requires an
  approved request.
- It must be requested **per bundle ID, and separately for every app extension** that needs it. A
  Screen Time product typically needs it for the main app, the DeviceActivityMonitor extension, and
  any shield configuration extension. Requesting only for the main app is a common and costly
  mistake.
- Apple requires a clear, genuine parental control or digital wellbeing use case.
- Typical approval is a few business days to a few weeks, and it can be refused.

**This is the longest external dependency in the project.** Apple's review time is outside our
control and outside the Sprint 0 timeline by agreement.

**Recommendation:** submit the request as soon as the client's Apple Developer account and bundle
identifiers are available, and submit for **all** targets at once. If iOS is confirmed in Phase 1,
this should not wait for design to finish.

### Client dependencies

Neither request can be submitted by us without the client providing:

| Item | Needed for |
|---|---|
| Google Play Console access or a publishing account | Declarations and submission |
| Apple Developer account (Organization) | Family Controls entitlement |
| Final bundle identifiers, all targets | Entitlement request per bundle ID |
| Final app name and branding | Store listings |
| Privacy Policy URL | Both stores — required, not optional |
| Terms and Conditions URL | Both stores |

The privacy policy is worth flagging: it must specifically describe the accessibility, notification
and screen capture usage. A generic policy will not survive review with this permission set.

## Recommended actions

**Immediately**
1. Confirm whether iOS is in Phase 1. If yes, request Family Controls for every target now.
2. Obtain the client's store accounts and final bundle identifiers.
3. Commission the privacy policy, drafted against the actual permission set.

**During Phase 1 design**
4. Design the §1C permission-introduction screens to satisfy Play's prominent disclosure and consent
   requirement, not only to reassure the parent. These are compliance surfaces.
5. Design the Android 17 Advanced Protection Mode state.

**Before first submission**
6. Decide on deferring NotificationListenerService. **Our recommendation is to defer.**
7. Prepare the AccessibilityService declaration and the review demo video.
8. Complete the Data Safety form.
9. Consider an internal-track submission early to surface policy feedback before the full build is
   finished. This is the single most effective way to de-risk the item, and it costs little.

## Sources

- [Play Console — Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Play Console — Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16585319)
- [Apple — Requesting the Family Controls entitlement](https://developer.apple.com/documentation/familycontrols/requesting-the-family-controls-entitlement)
- [Apple — Configuring Family Controls](https://developer.apple.com/documentation/xcode/configuring-family-controls)
- [Android 15 media projection changes](https://developer.android.com/media/grow/media-projection)
