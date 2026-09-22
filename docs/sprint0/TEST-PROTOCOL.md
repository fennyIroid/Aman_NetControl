# Sprint 0 POC Test Protocol

Exact steps to validate POC 1 and POC 2 on a device. Follow in order — the setup section is a
prerequisite for everything after it.

`PKG=com.example.childsafetyimpl`

---

## 0. Setup (once per device)

```bash
./gradlew installDebug
adb shell am start -n com.example.childsafetyimpl/.MainActivity
```

Grant the special accesses. **None of these are runtime dialogs** — each is a separate Settings
screen, which is why the Permissions screen in the app walks through them:

| Grant | Path |
|---|---|
| Usage Access | Settings → Apps → Special app access → Usage access |
| Display over other apps | Settings → Apps → Special app access → Display over other apps |
| Accessibility | Settings → Accessibility → Installed apps → Child Safety |
| Notifications | Runtime dialog on first launch |
| Battery unrestricted | Settings → Apps → Child Safety → Battery → Unrestricted |
| VPN consent | In-app: **Controls → Device-wide filtering → Start the tunnel** |

Verify everything is up before testing:

```bash
adb shell dumpsys activity services $PKG | grep -E "MonitoringService|ChildSafetyVpnService"
adb shell dumpsys activity services $PKG | grep -i "tun"
```

**Baseline capture** — record these before each test run so results are comparable:

```bash
adb shell settings get global private_dns_mode
adb shell settings get global private_dns_specifier
adb shell dumpsys deviceidle | head -5
```

---

## POC 2 — Core Protection and Anti-Bypass

Scenario IDs match `platform/validation/BypassScenario.kt`. Record Pass / Fail / N-A per device.

### A. Harmful website blocking

**`web.chrome`** — Open Chrome → `pornhub.com`.
✅ Expect: page fails to resolve. **Controls → tab → VPN protection → "Refused"** increments.
Also check **Events** tab for a "Blocked a restricted destination" entry naming the host only —
never a full URL.

**`web.other_browser`** — Repeat in Samsung Internet, Firefox, Brave.
✅ Expect: identical. Filtering is at DNS, not per browser. If one browser works, it has its own
DoH enabled — that's scenario `dns.browser_doh_hostname`, not a failure here.

**`web.direct_ip`** — Resolve a blocked domain on your laptop (`dig +short pornhub.com`), type the
raw IP into Chrome.
⚠️ Expect: **NOT blocked**. This is a documented platform limitation, not a bug. Record it as such.

### B. Safe Search

**`search.google_off`** — In Chrome, go to `google.com/preferences`, turn SafeSearch **off**, save.
Then search for something explicit.
✅ Expect: results still filtered, and the SafeSearch toggle is no longer changeable. Verify the
rewrite actually happened:

```bash
adb shell ping -c1 www.google.com    # should resolve to 216.239.38.120
```

**`search.country_domain`** — Try `google.ae`, `google.co.uk`, `google.com.eg`.
✅ Expect: all filtered (matched by pattern, not a fixed list).

**`search.youtube_app`** — Open the **YouTube app** (not the site), search for something borderline.
✅ Expect: Restricted Mode active. This is the one most likely to regress, because it depends on
`youtubei.googleapis.com` being rewritten — the app's data path, not just the website.

### C. Block-list and allow-list

**`rules.parent_domain`** — Controls → Blocked websites → add `example.com` → open it.
✅ Expect: refused.

**`rules.allow_only`** — Controls → turn on **Only allow sites you have listed** → add one site.
✅ Expect: only that site resolves.

**`rules.allow_only_breakage`** — ⚠️ **Leave allow-list mode on for 10 minutes and use the phone
normally.** Try the Play Store, expect push notifications, open an unrelated app.
⚠️ Expect: widespread breakage. **This is the known gap** — the system-traffic exemption isn't built
yet. Record what breaks; that list becomes the exemption set. **Turn the mode back off afterwards.**

### D. VPN detection and restriction

**`vpn.known_app`** — Install Proton VPN or Turbo VPN, open it.
✅ Expect: closed within ~2s, block screen shown, Events shows a VPN entry.

**`vpn.builtin_settings`** — Settings → Network → VPN → add an IKEv2 profile, connect.
✅ Expect: detected, tunnel reclaimed. Confirm ours holds the slot:

```bash
adb shell dumpsys connectivity | grep -iA3 "VPN"
```

**`vpn.revoke_consent`** — Settings → Network → VPN → gear icon next to Child Safety → disconnect.
⚠️ Expect: **detected, not prevented.** Critical event logged, tunnel re-established after ~1.2s.
Repeat the revoke rapidly — a determined child can outpace the reclaim. Record how many attempts it
takes. This is honest detection-only behaviour.

**`vpn.always_on_third_party`** — Settings → Network → VPN → set a third-party VPN as **Always-on**
with **Block connections without VPN**.
⚠️ Expect: our tunnel can no longer start, and the app says so. **Remember to undo this** — it will
block every subsequent test.

### E. DNS bypass — the important ones

**`dns.browser_doh_hostname`** — Chrome → Settings → Privacy → **Use secure DNS** → choose
Cloudflare from the dropdown. Browse to a blocked site.
✅ Expect: still blocked. Chrome's lookup for `chrome.cloudflare-dns.com` is refused, so it falls
back to the system resolver.

**`dns.browser_doh_bootstrap_ip`** — Chrome → secure DNS → **Custom** → enter `https://1.1.1.1/dns-query`
(an address, not a name). Browse to a blocked site.
✅ Expect: still blocked. **This is the new defence** — verify the counter moves:
**VPN protection screen → "Encrypted DNS packets dropped"** should increment. If it doesn't, the
host route isn't being installed.

**`dns.private_dns_hostname`** — the most effective bypass available:

```bash
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier dns.google
```

⚠️ Expect: **all filtering stops**, and within one monitoring tick the app raises a critical issue
naming `dns.google` with exact steps to undo it. Detection-only is the correct result.

```bash
# restore
adb shell settings put global private_dns_mode opportunistic
```

### F. Internet Pause — **new, test thoroughly**

**`pause.applies`** — **Controls → Internet pause → Pause the internet.**
✅ Expect within ~2s: browsing, streaming and messaging all stop, on **both** Wi-Fi and mobile data.
Test both — toggle Wi-Fi off and retry on cellular.

```bash
adb shell ping -c3 8.8.8.8    # should fail
```

**`pause.child_message`** — While paused, pull down the notification shade.
✅ Expect: an **ongoing, non-dismissible** notice: "Internet is paused… Nothing is broken."
❌ Fail if: apps show generic connection errors with no explanation anywhere. Also confirm apps fail
**fast** (~1s), not after a 30s hang — that's the refuse-don't-drop design.

**`pause.survives_reboot`** — While paused:

```bash
adb reboot
# wait for boot, unlock
adb shell ping -c3 8.8.8.8    # should STILL fail
```

✅ Expect: still paused, notice re-posted. ❌ **This failing is serious** — a child learns restarting
restores the internet.

**`pause.revoke_vpn`** — While paused, revoke VPN consent in Settings.
⚠️ Expect: internet returns **and** the Controls screen turns red with "You paused the internet, but
it is not being applied." The mismatch must be visible, not silent.

**Banking exclusion** (client-raised) — Install a banking app from the list in `TunnelExclusions.kt`.
✅ Expect: it works normally with the tunnel running. Then check it does **not** see the VPN:

```bash
adb shell dumpsys connectivity --diag | grep -i "uid"
```

### G. Persistence and tampering

**`persist.reboot`** — `adb reboot`, then time how long until enforcement resumes:

```bash
adb logcat -c && adb reboot
adb logcat | grep -i "MonitoringService"    # note timestamp vs boot
```

📊 **Record the boot-to-protection gap per device.** This is one of the two numbers that can only
come from hardware.

**`persist.force_stop`** — `adb shell am force-stop $PKG`
⚠️ Expect: everything stops. Nothing restarts it until the app is opened or the device reboots.
Detected afterwards via stale heartbeat (15 min threshold), not while it happens.

**`persist.battery_optimisation`** — Set battery back to **Optimised**, then:

```bash
adb shell dumpsys deviceidle force-idle
# leave 30+ min
adb shell dumpsys deviceidle unforce
```

📊 **Record heartbeat gaps.** Repeat on the Xiaomi/Transsion device — this is where OEM killers bite
and it's the largest source of real-world unreliability.

**`tamper.revoke_accessibility`** — Settings → Accessibility → turn Child Safety off.
⚠️ Expect: within one tick, a critical "Protection access was removed" event naming what stopped.
Shorts/Status control stop working immediately — verify by opening YouTube Shorts.

**`tamper.uninstall`** — Try to uninstall from Settings.
⚠️ Expect: blocked until device admin is deactivated. A deterrent, not a barrier — confirm you *can*
deactivate then uninstall.

---

## POC 1 — Values-Based Blur

Navigate: **Protections → Video blurring**.

### Setup

1. Toggle the feature on.
2. Tap **Start watching** → grant capture consent (Android shows the single-app/whole-screen choice).
3. Note: consent is **per session**. It will end on reboot or a call — that's expected behaviour,
   not a bug, and the resume notification is part of the design.

### A. Exposure window — the headline metric

This is the number POC 1 is judged on: **how long content is visible before it's covered.**

1. Start a session, open YouTube or Instagram.
2. Browse content that triggers covering for ~5 minutes.
3. Return to **Protections → Video blurring → Session**.

Read the **"Time on screen before covering"** block:

| Row | What it means |
|---|---|
| Average | Typical frame-arrival → cover-raised |
| Worst measured | Slowest sampled frame |
| **Worst case with sampling gap** | **The honest number.** Includes the up-to-500ms wait before anything looks at the frame |
| Of which classification | How much the model owns — where tuning would pay off |

📊 **Record all four per device.** Acceptance target is **1000 ms worst case**. The screen turns the
line red above it.

For a repeatable measurement rather than an impression, screen-record at 60fps and count frames
between content appearing and the cover landing:

```bash
adb shell screenrecord --time-limit 60 /sdcard/blur.mp4
adb pull /sdcard/blur.mp4
```

### B. Region blur — the client's reference behaviour

1. **Settings → What gets covered → "Cover people only".**
2. **Who to cover → "Anyone"** (leave gender off for this test).
3. Open content with people in it.

✅ Expect: only the people are covered with a soft-edged patch; the rest of the page stays readable.
One "Some of this is covered" chip at the bottom, not one per person.

Then check **Session → "Fell back to whole screen"**.
📊 **Record the fallback share.** This decides whether region mode is viable. Above ~40% the
detector is missing subjects and the mode is effectively behaving like whole-screen covering — the
screen says so explicitly.

### C. Gender targeting — expect the documented fallback

1. **Who to cover →** select **"People who appear female"** only.

✅ Expect: **everyone** is still covered, because no gender model is bound. This is the tested
fail-safe — covering everyone over-delivers on the parent's request, covering nobody would deliver
nothing behind a switch that reads as on.

❌ **Fail if nothing is covered.** That would mean the fail-safe broke.

The caption under the control must state it's best-effort. Binding a real model is a separate
decision — see the POC 1 document.

### D. Cover quality — verify it's a cover, not a tint

1. Set strength to **minimum**.
2. Trigger a cover over a photograph.
3. Screenshot, then inspect at 200% zoom.

✅ Expect: no silhouette readable at **any** strength setting. The floor is 0.955 alpha for exactly
this reason — at 85% a photo is still legible through the wash.

### E. FLAG_SECURE handling

1. With a session running, open a banking app (marks its windows secure).
2. Check **Session → "Frames that came back black"**.

✅ Expect: counted as blank, **not** scored as safe. Above 20% the screen shows a footnote. This
matters: "we weren't allowed to look" must never be reported as "nothing unsafe here."

### F. Performance

```bash
adb shell dumpsys batterystats --charged $PKG | grep -i "Foreground\|Power"
adb shell top -n 1 | grep childsafety
```

📊 Record CPU% and battery/hour with a session running vs not.

---

## Results template

Copy into the POC documents. **Fill only from runs you can reproduce — leave blanks rather than
estimates.**

| Scenario ID | Pixel | Samsung | Xiaomi/Transsion | Notes |
|---|---|---|---|---|
| web.chrome | | | | |
| web.other_browser | | | | |
| web.direct_ip | | | | expect limitation |
| search.google_off | | | | |
| search.country_domain | | | | |
| search.youtube_app | | | | |
| rules.parent_domain | | | | |
| rules.allow_only | | | | |
| rules.allow_only_breakage | | | | list what broke |
| vpn.known_app | | | | |
| vpn.builtin_settings | | | | |
| vpn.revoke_consent | | | | attempts to outpace reclaim |
| vpn.always_on_third_party | | | | |
| dns.browser_doh_hostname | | | | |
| dns.browser_doh_bootstrap_ip | | | | counter moved? |
| dns.private_dns_hostname | | | | detection latency |
| pause.applies | | | | Wi-Fi + cellular |
| pause.child_message | | | | |
| pause.survives_reboot | | | | |
| pause.revoke_vpn | | | | |
| persist.reboot | | | | **boot-to-protection gap** |
| persist.force_stop | | | | |
| persist.battery_optimisation | | | | **heartbeat gaps** |
| persist.oem_killer | | | | |
| tamper.revoke_accessibility | | | | detection latency |
| tamper.uninstall | | | | |

**POC 1:**

| Metric | Pixel | Samsung | Xiaomi/Transsion |
|---|---|---|---|
| Avg exposure (ms) | | | |
| Worst measured (ms) | | | |
| **Worst case incl. gap (ms)** | | | |
| Classification share (%) | | | |
| Subject fallback share (%) | | | |
| Blank frame share (%) | | | |
| CPU % during session | | | |
| Battery / hour | | | |

---

## Order of work

Day 1 — setup + section A–C (fast, mostly confirmation)
Day 2 — D, E, F (VPN, DNS, Pause — the new work)
Day 3 — G (persistence needs elapsed time)
Day 4–5 — POC 1 (needs real measurement, slowest)

Run **`pause.survives_reboot`**, **`dns.browser_doh_bootstrap_ip`** and the **POC 1 exposure
window** first if time is short. They cover the newest code and carry the most risk of surprising
you.
