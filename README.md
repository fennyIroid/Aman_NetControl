# Child safety App — child protection for Android

Ten protections that run on the device. Everything is judged locally, nothing is uploaded, and
where Android limits what is possible the app says so on the screen that claims the capability.

This is the working implementation. The feasibility study that preceded it lives in
`../ChildSafetyDemo`; this app takes the mechanisms that were shown to work and turns them into
protections a parent switches on.

---

## The protections

Ten protections in two groups.

**First priority**

| Protection | What it does |
|---|---|
| **VPN restriction** | Finds every app on the device that can create a tunnel, closes them as they open, and reclaims the single VPN slot Android allows so a third-party VPN cannot hold it. |
| **Dual and cloned app control** | Finds cloning tools and duplicate installs across every visible profile, notices new clones as they are created, and closes them on open. |
| **Shorts and Reels control** | Recognises the short-form feed inside YouTube, Instagram, TikTok, Facebook and Snapchat and leaves it. Can be refused outright or allowanced by the minute. |
| **Status and Stories control** | The same for WhatsApp Status, Instagram and Facebook Stories, Snapchat and Telegram. |
| **AI chat and game monitoring** | Measures time in AI apps and games against a shared daily budget, and scores what is typed into an AI for risk without storing any of it. |

**Everything else**

| Protection | What it does |
|---|---|
| **App restriction** | A blocked app cannot be opened. It is closed every time it comes to the front, usually within two seconds. This is how YouTube is restricted. |
| **Search and site filtering** | Answers search-engine lookups with the filtered address Google, Bing, DuckDuckGo and YouTube each publish, so SafeSearch applies in Chrome and cannot be switched off from inside it. Reads the address bar and search box of any browser and leaves a search for something restricted. |
| **Ad removal** | Refuses ad and tracker hosts device-wide through the tunnel, and strips ad requests and ad slots inside the built-in player and browser. |
| **Browser image blurring** | Every image the built-in browser requests is fetched, scored on the device and, if it fails, delivered blurred. The original never reaches the page. |
| **Video blurring** | Samples the screen a few times a second, judges each frame locally, and covers the picture while unsafe video plays. |

Two surfaces the app owns outright: a **supervised YouTube player** with Restricted Mode genuinely
on, ads refused and Shorts unreachable, and a **filtered browser** with site rules, safe search and
image blurring.

---

## Running it

```bash
./gradlew installDebug
```

Requires JDK 21 and Android SDK 37. Min SDK 24.

Most protections need a permission granted in Android Settings rather than through a dialog. Open
**Permissions** from the dashboard and work down the list; it is ordered so the top of it is the
shortest path to a protected device. Nothing pretends to work without its grant: a missing
permission produces an explicit "not running" state and names what is missing.

---

## What was verified on a device

Installed and exercised on a **Pixel 4, Android 13**.

- Blocking an app closed it on every launch, with the block screen naming the rule.
- The screen-reading service read YouTube's live view tree in 3 ms, correctly reporting "nothing
  restricted" on the home feed rather than firing on the wrong screen.
- The VPN guard detected a real third-party tunnel on `tun0`, raised a parent alert, and reported
  precisely why it could not reclaim it.
- The browser refused `bet365.com` at the navigation step, named the Gambling category, and never
  fetched the page.
- The supervised player loaded with Restricted Mode set and refused two ad requests on first load.
- Usage measurement returned live screen time, a per-app breakdown and a seven-day history.
- With usage access withheld, the dashboard showed "Screen time is unavailable" and named the
  missing grant, rather than a zero.
- With the tunnel running, `www.google.com` resolved to Google's forced-SafeSearch address and
  `m.youtube.com` to the restricted-mode one, while ordinary hosts resolved normally and
  DNS-over-HTTPS endpoints were refused.
- Searching for a restricted term in Chrome closed Chrome and raised a parent alert naming the
  category. An ordinary search in the same browser was left alone.
- A screen capture session started from the permissions list kept judging frames while the child
  moved between apps.
- Over an image search, the cover left no photograph on the screen recognisable, and lifted by
  itself once the frames came back clean.
- On the block screen, "Close" left the blocked app for the launcher, and "Ask a parent" opened
  this app on its permissions screen, whether or not it was already running.

---

## What it cannot do, stated plainly

- **Restricted Mode cannot be switched on inside the official YouTube app.** No API opens it. A
  parent who wants YouTube restricted switches the app off, which is enforced, and the child uses
  the built-in player instead.
- **A manufacturer's own clone space is invisible.** Samsung Secure Folder and similar are sealed
  off by Android; nothing inside one can be seen from here, and the clone screen says so.
- **Screen recognition depends on other apps' private view ids.** Instagram or YouTube can change
  them in any release. The protection screens report the live match rate so a decayed signature
  shows up as a falling number rather than a silent failure.
- **Back is a request, not a command.** An app that consumes it stays open, and the history records
  that rather than claiming a close that did not happen.
- **Video blurring cannot run unattended.** Android requires screen capture consent again every
  session and no app can hold it. When a session ends, a notification offers it back rather than
  the protection going quiet.
- **The cover over another app hides by weight, not by blurring.** A window can only blur what it
  draws itself, so the cover is a heavy wash: it leaves about one percent of the picture's contrast,
  which is enough to sense that an app is there and not enough to make out what is in it. From
  Android 12 the window manager is also asked to blur what is behind, but that is a bonus the
  system withdraws under battery saver and on hardware that cannot afford it, so the drawn cover
  has to be sufficient on its own. The pause control underneath stays touchable but is felt for
  rather than seen.
- **SafeSearch is the search engine's judgement, not ours.** It removes explicit imagery and is
  not a topic filter, which is why searches are read separately against a term list.
- **The restricted-term list is a floor.** A child who phrases a search around it will get
  through; what it catches is the ordinary case.
- **An app using DNS-over-HTTPS never passes through the tunnel.** That is detected by watching the
  Private DNS setting and reported, because no app can change it.
- **A blocked app flashes briefly before it closes.** Android gives a third-party app no callback
  for the foreground app changing; the usage event stream is the only route and it lags.

---

## Privacy

Nothing leaves the device. There is no backend and no network call carrying observed content.

What is deliberately never written to storage: message bodies, screen text, anything typed into an
AI, captured frames, and full URLs. Those are scored in memory and dropped inside the call that
received them. What survives is a length, a duration, a risk verdict, the categories that matched,
and a host name.

---

## Architecture

MVVM + UseCase with Hilt and Compose, following `CLAUDE.md`, which also documents the rules this
codebase follows and why breaking each one would make the app dishonest.

```
core/       protection catalog and live status, permissions, result wrapper
data/       Room, DataStore, repositories, curated lists
domain/     repository interfaces and models
platform/   the real Android mechanisms, one package per capability
di/         Hilt modules
navigation/ ViewModelNav, routes, the graph
ui/         theme, design system, illustrations, child-facing overlays
ux/         feature screens
```
