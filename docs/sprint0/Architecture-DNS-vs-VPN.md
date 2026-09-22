# Filtering Architecture — DNS vs VPNService

**Sprint 0 research note** · requested by the client
**Prepared by:** iRoid Solutions

---

## The question

The client tested two products and shared an observation:

> One similar to Kahf Guard, where the connectivity and configuration are primarily based on DNS.
> Another app called BlockerX, where the solution relies on a VPNService-based approach. […] we
> found the user experience of the DNS-based approach to be simpler and more convenient […] we also
> noticed that some banking and financial applications could object to or behave differently when a
> VPNService was active.

Both observations are correct. This note explains what produces them, and recommends a direction.

## The short answer

**The two products are not doing the same job, and that is what produces most of the UX
difference.** Kahf Guard is a content filter. BlockerX is a parental control suite. A content filter
can be delivered with DNS alone; a parental control suite cannot.

Of Aman's committed Phase 1 scope, most features never touch the network at all — app restriction,
screen time, routines, Shorts and Status control, clone control. Of the features that do, only
**Internet Pause** and **device-wide ad filtering** genuinely require a tunnel.

That is a much narrower requirement than "we need a VPN", and it points at a hybrid.

## What Kahf Guard actually does

Kahf Guard does not run a VpnService. It configures **Android's built-in Private DNS** setting
(Android 9+), which sends every DNS lookup on the device to a chosen resolver over TLS. Filtering
happens at that resolver. Traffic does not pass through Kahf's infrastructure.

This is why it feels lighter. There is no VPN key in the status bar, no per-session consent, and
nothing for a banking app to object to.

Two things are worth knowing before treating it as the obviously better path:

**It still needs an Accessibility service.** An app cannot write the Private DNS setting — Android
reserves that for a Device Owner. The parent types the hostname into Settings by hand, and a child
can untype it in the same three taps. Kahf Guard closes that gap with an Accessibility service that
watches the setting. So the accessibility dependency, and its Play policy exposure, does not go
away — it just moves.

**Per-family rules require a resolver that knows which family is asking.** Kahf Guard applies one
fixed server-side policy to everyone. A per-family block-list means issuing each family its own
resolver hostname, which means operating our own resolver — backend work, outside Sprint 0 but
inside Phase 1.

## The banking app problem is real, and it has a fix

Banking, payment and government identity apps check `NetworkCapabilities.TRANSPORT_VPN` and many
refuse to run when it is set, treating it as fraud risk. From the parent's side there is no
diagnostic: their bank worked yesterday, they installed a child-safety product, and now it does not.
The app gets uninstalled and every protection goes with it.

**Android provides the fix, and we have implemented it this sprint.**
`VpnService.Builder.addDisallowedApplication()` excludes an app from the tunnel entirely. An
excluded app's traffic never enters the tunnel, and — the part that matters — the network that app
sees is the underlying one, so `TRANSPORT_VPN` is not set for it and the check that would have
failed does not fire.

`TunnelExclusions` now carries a curated list of financial and identity apps weighted towards the
launch market: Gulf and wider MENA banking, regional wallets, and government identity apps (Absher,
UAE Pass, Metrash, PACI) which fail the same way and matter more.

The cost is stated rather than hidden: an excluded app's name lookups are not filtered. For a
banking app this is close to meaningless — it talks to its own bank's servers and is not a route to
anything the product blocks. It would be a real hole if the tunnel carried all traffic and the list
were long, which is why the list is restricted to financial and identity apps and **never** extends
to browsers or social apps. That restriction is enforced by an automated test.

## Comparison

| | Private DNS | VpnService (DNS-only, ours) | VpnService (full tunnel, BlockerX-style) |
|---|---|---|---|
| Setup | Parent types hostname into Settings | One consent dialog | One consent dialog |
| VPN icon in status bar | No | Yes | Yes |
| Banking app interference | None | Mitigated by exclusions | Likely, harder to mitigate |
| Covers Wi-Fi + mobile data | Yes | Yes | Yes |
| Survives reboot | Yes, it is a system setting | Needs re-establishing | Needs re-establishing |
| Battery cost | Negligible | Low — carries DNS only | Higher — carries everything |
| Category blocking | Yes, at the resolver | Yes, on the device | Yes |
| Safe Search forcing | Yes, at the resolver | Yes | Yes |
| Per-family lists, no backend | **No** | **Yes** | Yes |
| DoH/DoT bypass resistance | Weak | **Strong** — name and address | Strong |
| Ad filtering across apps | Partial | Yes | Yes |
| **Internet Pause** | **No** | **Yes** | Yes |
| Child can undo it | 3 taps in Settings | Revoke VPN consent | Revoke VPN consent |
| Needs Accessibility to hold | Yes, to guard the setting | No | No |

The two rows that decide it: **Internet Pause** is impossible with Private DNS alone — a resolver
cannot stop an open connection — and Internet Pause is committed Phase 1 scope. **Banking app
invisibility** is only available with Private DNS.

## Recommendation: hybrid

Run the quiet mechanism permanently and raise the intrusive one only when a feature needs it.

**Private DNS as the always-on baseline.** Per-family resolver hostname once the backend exists.
Covers category blocking and Safe Search with no VPN icon, no banking interference and no battery
cost. Survives reboot for free. Guarded by the Accessibility service against being changed back.

**The tunnel on demand.** Raised for Internet Pause and, if the client wants it always on, for
device-wide ad filtering. For pause it is up for the minutes the pause lasts and then gone.

**Everything else, no network involvement.** App restriction, screen time, routines, Shorts and
Status control, clone control all work through usage stats, accessibility and overlays. They are
unaffected by this decision entirely.

This is modelled in code as `FilteringMode` with a capability matrix, so the comparison the client
asked for and the behaviour the app implements are generated from the same source and cannot drift.

**One honest cost.** The posture changes, so a child watching the status bar can tell when a pause
begins. That is arguably correct — a pause is not meant to be a secret — but it is a real difference
from an always-present tunnel and should be a deliberate product decision rather than a surprise.

## Demonstrable in the app

All three postures are built, so the client can compare them on the same phone rather than from
this table. **Controls → How filtering works** switches between them:

- **Tunnel.** The behaviour the app has always had, and still the default, so nobody's filtering
  changes on update.
- **Private DNS.** No tunnel at all. The screen gives the hostname with a copy button, opens
  Settings, lists the steps (Android does not let an app write this setting), and shows the live
  status when the parent comes back. The monitoring loop reads the setting every two seconds and
  alerts the parent if it is cleared or pointed at an unfiltered resolver. Pause is shown as
  unavailable, with the reason.
- **Hybrid.** Private DNS day to day; the tunnel is raised only for Internet Pause and taken down on
  resume.

The capability list on that screen is generated from `FilteringMode.capabilities`, the same data
as the table above.

**Resolver used for the demonstration.** Per-family resolvers need the backend, so the setup screen
offers public family resolvers that `PrivateDnsGuard` already recognises: CleanBrowsing Family
(adult sites refused, SafeSearch forced) by default, and Cloudflare for Families. They show category
blocking and SafeSearch at the resolver. They cannot apply this family's own block and allow lists,
and the screen says so. That gap is the Phase 1 resolver work described below.

**Two behaviours worth knowing before the demo:**

1. **A pause under hybrid takes a moment longer to reach apps.** With the tunnel, a paused lookup is
   refused in milliseconds. Under hybrid, Android's Private DNS talks to the resolver over an
   encrypted connection that the paused tunnel drops rather than refuses, so an app waits for its
   own timeout. The child still sees the paused screen as soon as they open a browser, video, social
   or messaging app, because that is raised by the monitoring loop, not by the network.
2. **Ending another VPN in the Private DNS postures.** VPN restriction still reclaims the device's
   one VPN slot when a foreign VPN appears. In these postures it does that with a tunnel that
   carries nothing, so it never sits in front of the resolver, and gives the slot back after
   fifteen seconds.

## What this means for Sprint 0 and Phase 1

**Sprint 0.** No change to the POC 2 result. The tunnel is validated and works; the hybrid is a
deployment posture built on the same mechanisms, and is now selectable in the app for the
comparison. The banking exclusion is implemented and should be on the device test list, along with
the Private DNS setup flow and a pause under hybrid.

**Phase 1.** Two things follow.

1. **Operating a resolver becomes part of the backend scope.** Per-family hostnames, the category
   feed, and the DoT endpoint. This was not previously called out and affects the estimate.
2. **The setup flow gains a Private DNS step.** It cannot be automated — Android does not permit it —
   so it needs careful design. This is the one place where the "simpler" DNS approach is actually
   *harder* to set up than a VPN consent dialog, and the Figma should treat it as a first-class
   guided step rather than a settings link.

## Sources

- [Kahf Guard — Private DNS on Android](https://kahfguard.com/blog/private-dns/)
- [Kahf Guard FAQ (Android)](https://kahfguard.com/faq/) — confirms no VPN, and DNS-change protection via accessibility
- [Android developers — VPN](https://developer.android.com/develop/connectivity/vpn)
- [Android Enterprise — always-on VPN and lockdown](https://developer.android.com/work/dpc/network-telephony)
- [VPN detection on iOS and Android](https://tarkalabs.com/blogs/vpn-detection-guide-ios-android/) — `TRANSPORT_VPN` as the detection method
