# Test Scenarios and Fixtures

Concrete material to test with. Pairs with [TEST-PROTOCOL.md](TEST-PROTOCOL.md), which covers the
mechanics — this covers *what to point it at*.

**A note on test material.** You do not need explicit content to test this app, and using it would
actually test it badly. The bundled classifier detects swimwear, bathing and adult *settings* with
real confidence and only *infers* explicit content — that is the documented `PROXY_ONLY` finding in
POC 1. So beach and swimwear content is both the safe fixture and the more informative one. For
website blocking, verify at the DNS layer rather than loading pages: a refused lookup is the actual
mechanism under test, and it is faster to check.

---

## 1. Fixtures you control (start here)

The most reproducible tests use domains you add yourself. No dependency on a seed list, no
objectionable content, and the result is unambiguous.

**Setup:** Controls → Blocked websites → add each of these.

| Domain | Why this one |
|---|---|
| `neverssl.com` | Plain HTTP, never cached, never redirects. The cleanest possible "did it load" signal. |
| `example.com` | IANA reserved, stable forever, no CDN games. |
| `httpforever.com` | Plain HTTP alternative if neverssl is unreachable. |

**Verification without loading anything:**

```bash
# Before blocking — should return an address
adb shell ping -c1 neverssl.com

# After blocking — should fail to resolve
adb shell ping -c1 neverssl.com
```

Then check **VPN protection → "Refused"** incremented and **Events** shows the host (and *only* the
host — no path, no query string; that is Rule 3 of the codebase and worth confirming visually).

**Allow-list test:** turn on "Only allow sites you have listed", add `example.com` only.
`example.com` resolves, `neverssl.com` does not.

---

## 2. Built-in fixtures, by category

These ship in `SeedDomainRules.kt` and are already blocked by default.

### Safe to open directly

| Category | Test domains | Default |
|---|---|---|
| **Gambling** | `bet365.com`, `pokerstars.com`, `888casino.com`, `betway.com` | Blocked |
| **Dating** | `tinder.com`, `bumble.com`, `okcupid.com`, `badoo.com` | Blocked |
| **Malware** | `testsafebrowsing.appspot.com` | Blocked |

`testsafebrowsing.appspot.com` is Google's official safe-browsing test endpoint — it is designed
for exactly this and is completely safe.

### Verify at DNS only

| Category | Approach |
|---|---|
| **Adult** | `adb shell ping -c1 pornhub.com` → expect failure. Do not open a browser. |
| **Violence** | Same method against `bestgore.com`. |

### Category toggles — the more interesting test

Social and streaming default to **allowed**, because they are a parenting decision rather than a
safety absolute. That makes them the best fixtures for testing the category switches:

1. `instagram.com` and `youtube.com` load normally.
2. Controls → Blocked categories → turn on **Social networks**.
3. `instagram.com` now fails; `youtube.com` still loads.
4. Turn on **Video streaming** → `youtube.com` fails too.
5. Turn both off → both work again.

This exercises the whole path — toggle → DB write → filter cache refresh → DNS — and a cache that
fails to refresh is a setting that appears to save and does nothing.

---

## 3. Search-term guard

Type these into **Chrome's address bar** (not the app's own browser — the point is that it works in
browsers we do not own).

### Must be blocked

The non-adult categories are the better test material — safer to type, and they cover the gap
SafeSearch genuinely does not handle:

| Category | Terms |
|---|---|
| Gambling | `online casino`, `sports betting`, `csgo gambling`, `free bet` |
| Weapons | `buy a gun`, `ghost gun`, `3d printed gun`, `ammo for sale` |
| Drugs | `buy weed online`, `how to get high`, `dark web drugs` |

Expect: the search closes and a notice appears. **Events** records the category matched — never the
search text itself.

### Must NOT be blocked — run every one of these

This is the test most likely to be skipped and the one that decides whether a parent leaves the
feature switched on. Every item below contains a blocked term as a substring:

| Search | Contains | Must load |
|---|---|---|
| `scunthorpe united` | the classic | ✅ |
| `essex county council` | "sex" | ✅ |
| `sextant navigation` | "sex" | ✅ |
| `grapes of wrath` | "rape" | ✅ |
| `weeding the garden` | "weed" | ✅ |
| `risk assessment paper` | "ass" | ✅ |
| `photosynthesis explained` | "synthesis" | ✅ |
| `flu shot appointment` | "shot" | ✅ |
| `high school ranking` | "high" | ✅ |
| `premier league table` | — control | ✅ |
| `minecraft redstone tutorial` | — control | ✅ |

❌ **Any of these being blocked is a release blocker.** A filter that closes a homework search gets
switched off, and every other protection goes with it.

### Scope check

Open a **news article** that legitimately discusses drugs or weapons — a BBC or Reuters piece.
✅ Expect: it loads fine. The guard reads the address bar and search fields only, never page body
(Rule 8). If the article closes, the guard is reading too much.

---

## 4. Apps to install

### VPN restriction

| App | Package | Tests |
|---|---|---|
| Proton VPN | `com.protonvpn.android` | Known-list detection |
| Turbo VPN | `free.vpn.unblock.proxy.turbovpn` | Known-list detection |
| **Any obscure VPN from Play** | — | **Unknown-app path — the more valuable test** |

The unknown-app case matters more: it proves detection does not depend on the curated list. Pick
something with under 10k installs.

**Also test the built-in path:** Settings → Network → VPN → add an IKEv2 profile. No app involved.

### Shorts / Reels / Status surfaces

| App | Surface to open |
|---|---|
| YouTube | Shorts tab |
| Instagram | Reels tab |
| TikTok | Main feed |
| Facebook | Reels |
| WhatsApp | Status tab |
| Snapchat | Stories |

⚠️ **These break on app updates** — they match private view IDs. If one stops working, that is the
signature-fragility finding, not a regression. Note the app version in your results; that is the
evidence for the server-delivered signatures recommendation.

### Clone / dual-app

| Route | Tests |
|---|---|
| Samsung **Dual Messenger** (Settings → Advanced features) | OEM cloning — most common in the launch market |
| **Island** or **Parallel Space** from Play | Third-party cloning tool |
| Samsung **Secure Folder** | Should be reported as a limitation, not silently missed |

### Banking exclusion (client-raised)

Install any bank from `TunnelExclusions.kt`. With the tunnel running:
✅ Expect: the app opens and works normally, with no VPN complaint.
Then confirm a **non-excluded** app still gets filtered — that the exclusion is surgical, not a hole.

---

## 5. Blur test content

### Whole-screen mode — content that actually triggers the model

Search YouTube for these. All safe, all publicly available, all firing the labels the model is
genuinely confident about:

| Search | Fires | Category |
|---|---|---|
| `beach vacation vlog` | Swimwear, Bathing | Suggestive |
| `swimming competition highlights` | Swimwear, Water | Suggestive |
| `surfing compilation` | Swimwear, Wetsuit | Suggestive |
| `bodybuilding posing routine` | Muscle, Flesh, Skin | Suggestive |
| `las vegas casino tour` | Casino, Gambling | Gambling |
| `slot machine jackpot` | Gambling | Gambling |
| `whisky tasting review` | Alcohol, Drink | Substance |

Turn on the matching category in **Settings → What gets covered** before each. Then verify the
inverse: with **only** "Explicit imagery" enabled, the casino video must **not** be covered. That
proves category gating works and the score is not being used on its own.

### Must NOT be covered — false positives

| Content | Why it is the trap |
|---|---|
| `face reveal vlog` / any talking-head video | High skin ratio, close-up face |
| `makeup tutorial` | Very high skin ratio, sustained |
| `baby first steps` | Skin tone plus small figures |
| `desert landscape 4k` | Warm rock reads as skin tone to a pixel scorer |
| `wood carving asmr` | Same — warm tones, low edge density |
| `sepia historical photographs` | The classic pixel-heuristic failure |

❌ Any of these being covered means the fusion rule has regressed — pixel evidence is flagging on
its own, which is exactly what `ImageFusionTest` exists to prevent.

### Subject mode

| Content | Checks |
|---|---|
| `news anchor broadcast` | Single, stable, front-facing subject — the easy case |
| `group interview panel` | Multiple simultaneous regions |
| `crowd at a concert` | Many small faces — expect the `MIN_FACE_FRACTION` floor to skip background |
| `dance performance` | Fast movement — exposes region lag at 2fps |
| `person filmed from behind` | **No face → expect whole-screen fallback** |
| `animated cartoon` | Should find no faces at all |

📊 The "filmed from behind" case is the important one: it should trigger the documented fallback,
and it is what drives the **subject fallback share** figure that decides whether region mode ships.

### Gender targeting

Any video with visibly mixed subjects — a panel show or news desk.
Select **"People who appear female"** only.
✅ Expect: **everyone** covered (no model bound — the tested fail-safe).
❌ Fail only if *nothing* is covered.

### FLAG_SECURE

Open a banking app with a capture session running.
✅ Expect: counted under "Frames that came back black", **not** scored as safe.

---

## 6. End-to-end scenarios

Run these as narratives. They catch interaction bugs that per-feature tests miss.

### Scenario A — "The determined evening"

Simulates a child working through bypasses in order of sophistication.

1. Open `bet365.com` in Chrome → blocked
2. Switch to Samsung Internet, retry → blocked
3. Turn on Chrome secure DNS (Cloudflare, by name) → still blocked
4. Switch Chrome secure DNS to custom `https://1.1.1.1/dns-query` → **still blocked** (new defence)
5. Install a VPN app, open it → closed on sight
6. Settings → Private DNS → `dns.google` → **bypass succeeds**, critical alert raised
7. Restore Private DNS → filtering resumes

✅ Every step recorded in Events with the right severity. Step 6 is the honest failure and must be
loudly visible, not buried.

### Scenario B — "Homework night"

The false-positive run. Nothing here may be blocked.

1. Search `photosynthesis explained`
2. Search `risk assessment paper`
3. Search `grapes of wrath summary`
4. Open a news article about drug policy
5. Watch `minecraft redstone tutorial` on YouTube
6. Open a makeup tutorial with blur running

✅ All six complete uninterrupted. **This is the trust test** — one wrong block here and a parent
disables the product.

### Scenario C — "Pause during a tantrum"

1. Child is watching YouTube
2. Parent: Controls → **Pause the internet**
3. Video stops within ~2s; the child sees the paused notice, not a network error
4. Child opens Chrome → fails **fast** (~1s), not a 30s hang
5. Child opens a downloaded game → **still works** (offline apps unaffected)
6. Child restarts the phone → **still paused**
7. Child revokes VPN consent → internet returns, **Controls turns red**
8. Parent resumes → back to normal filtering

Steps 5, 6 and 7 are the ones that matter. Step 5 proves the pause is proportionate; 6 proves
persistence; 7 proves the mismatch is visible rather than silent.

### Scenario D — "The weekend degradation"

Tests whether protection quietly dies.

1. Set battery to **Optimised**
2. `adb shell dumpsys deviceidle force-idle`
3. Leave overnight
4. Next morning: `adb shell dumpsys deviceidle unforce`
5. Open the app

✅ Expect: either enforcement continued, or the gap is reported. ❌ **Fail if it shows a calm green
screen after hours of not running** — that is the exact failure the health model exists to prevent.

Repeat on the Xiaomi/Transsion device. This is where the OEM finding comes from.

### Scenario E — "Fresh install, real setup"

1. `adb uninstall com.example.childsafetyimpl`
2. Install, and complete setup **only through the in-app Permissions screen**
3. Time it, and note every point you had to leave the app

📊 Record the count of Settings round-trips. This is direct input to the Figma for §4B, and it is
the number that decides whether parents finish onboarding.

---

## 7. Quick fixture reference

```bash
PKG=com.example.childsafetyimpl

# Blocked by default
bet365.com  pokerstars.com  tinder.com  bumble.com  testsafebrowsing.appspot.com

# Allowed by default (for category-toggle tests)
instagram.com  youtube.com  reddit.com  twitch.tv

# Add yourself
neverssl.com  example.com

# Must never be blocked (search guard)
"scunthorpe united"  "essex county council"  "grapes of wrath"
"weeding the garden"  "risk assessment"  "flu shot"  "photosynthesis explained"

# Private DNS bypass on / off
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier dns.google
adb shell settings put global private_dns_mode opportunistic

# Doze
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce

# Tamper
adb shell am force-stop $PKG
```
