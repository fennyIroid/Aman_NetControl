# Android vs iOS Scope Notes

**Sprint 0 Deliverable 4** · Updated platform behaviour and feasibility matrix
**Prepared by:** iRoid Solutions

---

## How to read this

Three labels, used consistently and matching the Figma convention the design scope asks for:

- **Common** — same behaviour, both platforms
- **Android** — Android only; iOS has no equivalent
- **iOS-limited** — available on iOS but in a weaker or different form

The rule this document exists to enforce: **the UI must never promise Android behaviour on iOS.**
Where a capability does not exist on iOS, the entry names the closest supported alternative rather
than leaving a gap for the design to fill optimistically.

## Why the platforms differ at all

One architectural fact explains almost every row below.

**Android lets an app observe and act on other apps**, through AccessibilityService (read another
app's screen), UsageStatsManager (which app is in front), SYSTEM_ALERT_WINDOW (draw over another
app) and VpnService (carry the device's traffic).

**iOS does not.** Apple's parental control surface is the Screen Time family of APIs —
FamilyControls, ManagedSettings, DeviceActivity — which are deliberately built so the controlling
app never sees what the child is doing. It can shield an app, set a time budget, and be told a
threshold was crossed. It cannot read a screen, identify a section within an app, or draw over
another app. Apple treats that opacity as the feature, not the limitation.

The consequence: **iOS can enforce whole-app rules well and cannot do anything inside another app.**

## Feasibility matrix

### Network and web

| Capability | Android | iOS | Label |
|---|---|---|---|
| Harmful category blocking | Yes — DNS filtering | Yes — Managed Settings web filter / network extension | Common |
| Custom block-list | Yes | Yes | Common |
| Allow-list-only mode | Yes¹ | Yes | Common |
| Safe Search forcing | Yes — DNS rewrite, all browsers | Partial — Apple's own filter, less control | iOS-limited |
| YouTube Restricted Mode | Yes — covers the app's data path | Partial — web only, not the app | iOS-limited |
| DoH/DoT bypass blocking | Yes — name and address | Limited | iOS-limited |
| Ad filtering across apps | Yes — DNS | No — only in our own browser | Android |
| **Internet Pause** | Yes | Partial — network extension, weaker guarantees | iOS-limited |
| VPN detection | Yes | Limited | iOS-limited |
| VPN app restriction | Yes — closed on open | No | Android |

¹ Needs the system-traffic exemption described in the POC 2 results before it is shippable.

### Apps and time

| Capability | Android | iOS | Label |
|---|---|---|---|
| Whole-app blocking | Yes — overlay on foreground | Yes — Managed Settings shield | Common |
| Per-app daily limits | Yes | Yes — DeviceActivity | Common |
| Overall screen time | Yes | Yes | Common |
| Routines / schedules | Yes | Yes | Common |
| App usage reporting | Yes — per app, detailed | Yes — within Apple's privacy model | iOS-limited |
| Custom block screen | Yes — full control of the design | Partial — ShieldConfiguration, constrained | iOS-limited |

### Content inside other apps

This block is where the platforms genuinely diverge, and it is the one most likely to be
over-promised in design.

| Capability | Android | iOS | Label |
|---|---|---|---|
| Shorts / Reels control | Yes | **No** | Android |
| Status / Stories control | Yes | **No** | Android |
| Values-based Blur in third-party apps | Yes¹ | **No** | Android |
| Values-based Blur in Aman's own browser | Yes | **Yes** | Common |
| Dual / cloned app control | Yes | **N/A** — iOS has no cloning concept | Android |
| AI chat risk signals | Partial — accessibility | **No** | Android |
| Message safety signals | Partial — notifications | **No** | Android |

¹ Subject to the MediaProjection constraints in the POC 1 results — not "always on".

**The iOS alternative for every row marked No is the same: whole-app control plus an app timer.**
The design scope already states this and Sprint 0 confirms it. Aman on iOS can block Instagram
entirely or allow it with a 30-minute budget; it cannot allow Instagram while restricting Reels.

### Protection integrity

| Capability | Android | iOS | Label |
|---|---|---|---|
| Survives restart | Yes | Yes — stronger, enforcement is the OS | Common |
| Tamper detection | Yes — detection only | Yes — authorisation revocation is observable | Common |
| Prevent uninstall | Deterred — device admin | Yes — with Screen Time passcode | iOS-limited¹ |
| Prevent permission removal | No — Device Owner only | Yes — parent's passcode protects it | iOS-limited¹ |

¹ Marked iOS-limited only because the mechanism differs. **On protection integrity, iOS is
genuinely stronger than Android** for a non-provisioned install — the controls are enforced by the
OS and sit behind the parent's Screen Time passcode. This is worth stating to the client, because it
runs opposite to every other row in this document.

## Platform changes identified during Sprint 0

**Android 17 Advanced Protection Mode.** Revokes the AccessibilityService grant from apps that are
not accessibility tools, and blocks it being re-granted while active. Parental controls are outside
Google's definition of an accessibility tool, so Aman cannot claim exemption. The mode is user
opt-in, so this is bounded rather than a blanket block — but when it is on, Shorts control, Status
control and the search-term guard all stop. **Needs a protection-health state in the design.**

**Android 15 media projection.** Defaults to single-app sharing, and Android 15 QPR1+ shows a
prominent status-bar chip the user can tap to stop sharing. Continuous capture is never invisible
and the child can always stop it. **Needs to be reflected in the Values-based Blur design rather
than shown as a silent background protection.**

**Android 14 capture consent.** Per-session and cannot be saved. "Always-on video blur" is not
achievable from a Play Store app on Android. The session-ended and resume states are a core part of
the Values-based Blur design, not an error path.

## Design guidance

**Mark as Android-only in Figma:** Shorts/Reels control, Status/Stories control, cloned app control,
VPN app restriction, ad filtering across apps, Values-based Blur in third-party apps, AI chat and
message signals.

**Design a distinct iOS setup flow.** Android is install → grant six special-access permissions,
each from a different Settings screen. iOS is install → Family Controls authorisation → pair with
the child's Apple ID. These have nothing in common and must not share a screen design. The design
scope already calls this out; it is worth repeating because it is the most common place this goes
wrong.

**Design the "not available on this platform" state as a first-class state.** The design scope asks
for it under empty and error states, and this matrix is the list of places it will actually appear.
It should explain what the platform does not allow and what the closest available control is — not
simply hide the feature, which leaves a parent comparing two devices and concluding the app is
broken on one of them.

**Do not show these as confirmed on iOS**, consistent with the client's own list: Reels-only
control, WhatsApp Status-only control, video blurring inside third-party apps, cloned app control.

## Open items for the alignment meeting

1. **Is iOS in Phase 1?** It roughly doubles the work and its ceiling is materially lower for the
   content-control features the client prioritised. If it is in, the Family Controls entitlement
   request should go out immediately — Apple's review time is outside our control and is explicitly
   outside the Sprint 0 timeline.
2. **Device Owner on Android?** Covered in the POC 2 results. Changes the Android setup journey
   substantially and therefore the Figma.
3. **How should platform difference be presented to a parent?** Options are to show the union of
   features with per-platform availability marked, or to show each platform only what it can do. The
   first is honest but can read as a list of things the product cannot do; the second is cleaner but
   makes a parent with one of each device think something is broken. This is a design decision the
   client should make explicitly.
