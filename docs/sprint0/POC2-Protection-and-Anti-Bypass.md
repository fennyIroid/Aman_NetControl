# POC 2 — Core Protection and Anti-Bypass Engine

**Sprint 0 Deliverable 3 (part 2 of 2)** · Android
**Status:** engineering complete, device validation pending
**Prepared by:** iRoid Solutions

---

## 1. What this POC had to answer

The Sprint 0 scope asks POC 2 to validate harmful website blocking, Safe Search, custom block-list
and allow-list behaviour, supported VPN detection and restriction, DNS and network bypass
scenarios, one-tap Internet Pause and Resume, protection persistence after restart, and permission
removal or tampering — and then to state clearly:

> which bypass scenarios can be prevented, which can only be detected and alerted, and which
> remain platform limitations.

That sentence is the deliverable. Section 4 answers it directly.

## 2. Headline result

**Verdict: Go, with one architectural decision required before Phase 1.**

The protection architecture is strong enough to proceed into production. Every area named in the
POC 2 scope is implemented and working on Android. Of 35 bypass scenarios catalogued, the majority
are prevented outright, and every one that is not carries a documented reason and a stated remedy.

The decision that has to be made, and that determines a significant share of the product's
real-world strength, is **whether Aman ships as an ordinary Play Store app or as a Device Owner
provisioned app**. Section 6 sets out what changes between the two. It is not primarily a technical
question — it is a question about the setup experience the client is willing to ask parents to go
through — and it should be answered in the Sprint 0 alignment meeting rather than deferred.

## 3. What was built

All of the following is implemented, compiles, and is covered by automated tests. It is not a
mock-up or a prototype path.

| POC 2 area | Mechanism | State |
|---|---|---|
| Harmful website blocking | DNS refusal inside the filtering tunnel | Working |
| Safe Search | Lookup rewritten to each engine's published filtered address | Working |
| Custom block-list | Per-domain parent rules evaluated on the device | Working |
| Allow-list-only mode | Nothing resolves unless listed, except a fixed set of system hosts | Working — the 5.3 caveat is fixed |
| VPN detection | Two independent checks: transport capability and interface scan | Working |
| VPN restriction | Known VPN apps closed on open; foreign tunnels reclaimed | Working |
| DNS/DoH bypass | Resolver hostnames refused **and** resolver addresses dropped | Working, **new in this sprint** |
| Private DNS bypass | Detected, classified and explained to the parent | Detection only — see 5.1 |
| Filtering posture | Tunnel, Private DNS, or hybrid, chosen on the Controls screen | Working, **added after the architecture comparison** |
| Internet Pause / Resume | Tunnel switched to a drop-everything posture; one tap from the dashboard and Controls | Working, **new in this sprint** |
| Restart persistence | Boot receiver restores enforcement and any active pause | Working |
| Tamper detection | Grant-state comparison plus enforcement heartbeat | Working |
| Protection health | Four-state model with what/why/how-to-fix per issue, shown on the dashboard | Working, **new in this sprint** |

### 3.1 Work completed during this sprint

Four things did not previously exist and were built for this POC.

**One-tap Internet Pause and Resume.** The tunnel now has two postures. In its normal filtering
posture it carries name lookups and nothing else. When a parent pauses, it rebuilds with a default
route and refuses everything. Because the pause sits underneath the radios it covers Wi-Fi and
mobile data together, which is what an app can do — an app cannot switch off a radio on a modern
Android release, and one that could would be indistinguishable from malware.

Two design decisions inside it are worth surfacing because they are product decisions, not
implementation details:

- **Name lookups are refused, not dropped.** A dropped lookup leaves every app spinning for around
  thirty seconds and the phone reads as broken. A refused one fails in milliseconds, so the child
  reaches the explanation instead of a connection timeout. The brief's requirement to avoid a
  generic network failure is met by this plus an ongoing notice.
- **The paused state is persisted before it is applied.** A pause a restart silently lifts is worse
  than no pause, because the parent believes it is still in force. If the process dies between the
  write and the apply, the device comes back paused — the safe direction to fail in.

**Address-level encrypted-DNS blocking.** Refusing DoH resolvers by hostname — the previous
behaviour — is defeated by configuring a resolver by IP, because no name lookup happens. The tunnel
now also takes a host route for each well-known resolver address and drops traffic sent there.
These are single-address routes, so the tunnel still carries almost nothing and a bug cannot take
the device offline.

**Protection health model.** A four-state model (Active / Degraded / Not running / Tampered with)
where every issue carries what is wrong, why it matters for this child, and how to fix it. The
distinction between *Not running* and *Tampered with* is deliberate and load-bearing: one is a
parent's decision and the other is a child's, and a product that shows the same screen for both has
failed at its only job.

**Banking and identity app exclusion.** Addressed in detail in the separate
[architecture comparison](Architecture-DNS-vs-VPN.md), raised by the client directly.

## 4. Bypass classification — the required answer

Full catalogue: `platform/validation/BypassScenario.kt` (35 scenarios). It is held in code rather
than in a spreadsheet so the claims the app makes on screen and the claims validated here cannot
drift apart. Automated tests enforce that nothing is classified as prevented without a mechanism,
and that no tampering scenario is ever claimed as prevented.

### 4.1 Prevented

The child does not get the thing they were trying to get.

| Scenario | Note |
|---|---|
| Blocked site in Chrome, Samsung Internet, Firefox, Brave, Opera | Filtering is at the DNS layer, so it is not per-browser |
| SafeSearch switched off inside Google's own settings | The filtered address offers no control to switch it off |
| Switching to a Google country domain (`google.ae`, `google.co.uk`…) | Matched by shape, not by a fixed list |
| Searching inside the YouTube app rather than the website | The app's own data path is covered, not just the site |
| Parent-added domain | |
| Allow-list-only mode | Only listed names resolve |
| Known VPN app opened | Closed on foreground |
| Unknown VPN app | Tunnel detected and reclaimed; does not depend on naming |
| VPN configured in Android Settings (IKEv2/L2TP) | |
| Browser secure DNS selected by provider name | Falls back to the filtered system resolver |
| Browser secure DNS pointed at a resolver **by IP** | Host routes drop it |
| Internet pause applies across Wi-Fi and mobile data | |
| Internet pause survives a restart | |
| Internet pause survives the process being killed | The tunnel reads the pause on every start; the monitor re-applies it |
| Allow-list-only mode leaves the phone working | System hosts exempt, never ahead of a parent's block |
| Child sees an explanation, not a network error | |
| Protection restarts after reboot | |

### 4.2 Detected and reported, but not prevented

We see it, we alert, we cannot stop it. This is where the Device Owner decision bites.

| Scenario | Why it cannot be prevented |
|---|---|
| Private DNS pointed at another provider | Android does not let any app write this setting |
| Third-party VPN set always-on with lockdown | Android refuses the tunnel to anything else |
| VPN consent revoked | Re-established after a short delay; a determined child can repeat it faster |
| Internet pause stops applying after consent revoked | Surfaced as its own issue rather than left silent |
| Accessibility service turned off | No API notifies an app that its grant was removed |
| Usage Access revoked | Reported as unavailable, never as a plausible zero |
| App force-stopped from Settings | Detected afterwards via stale heartbeat, not while it happens |
| Battery optimisation re-enabled | Doze is a platform guarantee |
| OEM background killers | Varies by manufacturer; the largest source of real-world unreliability |
| App uninstalled | Device admin makes it require deactivation first — a deterrent, not a barrier |
| Android Advanced Protection Mode enabled (Android 17+) | See 5.4. Reported as the OS's decision, not as tampering |

### 4.3 Platform limitations

Neither prevented nor reliably detected.

| Scenario | Note |
|---|---|
| Site reached by raw IP address | The tunnel routes DNS only, by design |
| Blocked site via a web proxy or translate service | Category-coverage problem, not architecture |
| DoH to a self-hosted or obscure resolver by IP | Address list is a list, not a proof |
| Tethering to a second device | Does not traverse this device's VPN |
| Safe-mode boot | Android disables third-party apps by design |
| App storage cleared | No callback exists; argues for a backend holding configuration |

## 5. Findings that need a decision

### 5.1 Private DNS is the most effective bypass on an unmanaged device

Three taps in Settings sends every lookup on the device to an encrypted resolver we cannot see.
Nothing in the filter applies after that. We detect it within one monitoring tick and give the
parent exact steps to undo it, and that is the ceiling for an app installed from the Play Store.

Kahf Guard — which the client tested — solves this with an Accessibility service that watches the
setting. We watch it from the monitoring loop instead, which reads the setting every two seconds
without needing the accessibility grant (and so without the 5.4 risk). In the Private DNS and hybrid
postures a change is raised as a parent alert and as a dashboard health issue; in the tunnel
posture it is raised as before.

### 5.2 Encrypted-DNS address blocking is coverage, not proof

The list covers the resolvers people actually use. A resolver not on it, reached by address, still
works. Closing this properly needs full-traffic routing with SNI inspection, which is a materially
different product with materially worse battery life and app compatibility. **Recommendation:** do
not pursue it. The residual risk requires a level of deliberate effort well beyond the threat model
for a child-safety product, and the cost falls on every user.

### 5.3 Allow-list-only mode — fixed

With allow-list-only on, app updates, push notifications and CDN-hosted assets all stopped
resolving. The phone did not read as restricted, it read as broken — which produces a factory reset
and a support call.

**Now fixed.** `SystemTrafficExemptions` keeps a fixed set of system hosts reachable: connectivity
checks, push delivery, Play Store downloads and updates, account sign-in and time. It is consulted
only after the parent's own rules and the blocked categories, so it can never outrank a block, and
a test holds the line that nothing browsable is ever on it — no search engine, video site, social
network, or general-purpose CDN.

**The residual limit, stated.** A listed website whose images come from a separate CDN host may
load without them until the parent lists that host too. General CDNs are deliberately not exempt,
because a whole website can be served from one. A licensed category feed that pairs sites with their
asset hosts is the production answer.

### 5.4 Android 17 Advanced Protection Mode — new platform risk

Android 17 introduces Advanced Protection Mode, which revokes the AccessibilityService grant from
apps that are not accessibility tools and prevents it being granted again while the mode is on.
Parental control apps are explicitly outside Google's definition of an accessibility tool, so Aman
cannot claim the exemption.

**Bounded, but real.** The mode is opt-in by the user, so it is not a blanket block. When it is on,
the protections that depend on accessibility — Shorts and Reels control, Status and Stories control,
the search-term guard and AI chat monitoring — stop.

**Now implemented as its own health state.** `AdvancedProtectionProbe` reads the mode through
`AdvancedProtectionManager` (Android 16 and later). While it is on, the accessibility revocation is
not reported as the child tampering: the dashboard shows an "Android Advanced Protection is on"
issue that says which protections stopped and why, and the parent is alerted once. Any other grant
removed at the same time is still reported as tampering.

Nothing can change the underlying behaviour. It is a deliberate platform decision and the correct
response is honest messaging. The Figma should carry the same state.

## 6. The Device Owner decision

A significant share of section 4.2 moves to 4.1 under Device Owner provisioning: always-on VPN with
lockdown, Private DNS set and locked, usage access granted by policy, the app made non-stoppable and
non-uninstallable, safe mode blocked, tethering disabled.

The cost is the setup experience. Device Owner requires provisioning at factory-reset time (QR code
or NFC) and cannot be granted to an app installed normally from the Play Store. In practice it means
either a factory reset of the child's existing phone, or shipping on a device set up for the purpose.

|  | Play Store install | Device Owner |
|---|---|---|
| Setup | Install, grant 6 permissions | Factory reset, QR provisioning |
| Tamper resistance | Detection only | Genuine prevention |
| Uninstall | Deterred | Blocked |
| Always-on VPN | Cannot be forced | Enforced |
| Private DNS | Read-only | Set and locked |
| Market reach | Everyone | Parents willing to reset the device |

**Our recommendation:** ship Play Store as the primary path, and design the setup flow so Device
Owner can be offered as an optional "maximum protection" mode later without rework. Most competitors
in this category are Play Store apps and accept detection-only tamper resistance; the honest
messaging in the protection health model is what makes that acceptable. But this is the client's
call, and the Figma work depends on it because the Android setup journey differs substantially
between the two.

## 7. Device validation — to be completed

The engineering is done; the device matrix is not. Per the Sprint 0 scope this tests representative
high-risk scenarios rather than every OEM.

**Proposed device set** (to be confirmed at kickoff):

1. **Pixel** — clean AOSP baseline behaviour
2. **Samsung** — largest market share, plus Secure Folder for clone testing
3. **Xiaomi or Transsion** — aggressive background management, realistic for the launch market

**Results table — to be filled from device runs.** No values are entered below. Anything recorded
here must come from a run someone can reproduce.

| Scenario ID | Pixel | Samsung | Xiaomi/Transsion | Notes |
|---|---|---|---|---|
| *(35 scenarios from the catalogue)* | — | — | — | — |

Two figures in particular can only come from hardware and matter more than the rest:

- **Boot-to-protection gap.** How long after a restart before enforcement resumes, per OEM.
- **Heartbeat continuity over 24h idle.** Where the OEM killers actually bite.

## 8. Recommended production direction

1. **Proceed.** The architecture is sound and the gaps are understood.
2. **Resolve the Device Owner question** in the alignment meeting — it gates the Android setup UX.
3. **Adopt the hybrid filtering posture** described in the [architecture comparison](Architecture-DNS-vs-VPN.md).
4. ~~Schedule the allow-list exemption work~~ — done in Sprint 0 (5.3).
5. **Carry the Advanced Protection health state into the design.** Implemented in the app (5.4);
   the Figma needs the matching state.
6. **Budget for a licensed category feed.** The bundled list is a demonstration list. Real coverage
   is a commercial feed, and Arabic-language harmful sites are poorly covered by Western feeds —
   this needs specific attention for the launch market.
