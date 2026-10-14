# Harmful Website Blocking — Response to the Product Owner's Review

Prepared by iRoid Solutions, 9 October 2026. Android only. Answers the four points in the Product
Owner's review of the Sprint 0 POC, in order. Every coverage figure here was **measured by us** on
9 October 2026, not taken from a vendor's website; how to repeat the measurement is at the end.

## Summary

| # | Requirement | Status |
|---|---|---|
| 1 | No reliance on a local VPN / `VpnService` for filtering | **Done.** Filtering is Android Private DNS only. No VPN slot is held, so banking and trading apps see no VPN. |
| 2 | DNS-based filtering, like KAHF Guard | **Done.** Same architecture as KAHF Guard, which we verified (below). |
| 3 | Broad, maintained coverage including Arabic sites, competitors verified | **Measured.** The resolver we chose as the default turned out to be the weakest on Arabic sites. A decision is needed (below). |
| 4 | Stop IP-address and other bypasses without disrupting normal use | **Done for browsers.** Raw IPs, disguised IPs, translation/cache relays and `user@` tricks are now refused. Remaining limits are listed. |

## 1. No local VPN

Website filtering no longer uses `VpnService` at all. The phone's own Private DNS setting sends
every app's name lookups to a filtering resolver over an encrypted connection. Nothing passes
through our app, no VPN key icon appears, and apps that refuse to run under a VPN are unaffected.

One feature still raises a VPN, only while it is in use: **Internet Pause**. A DNS resolver can
refuse a name but cannot stop a connection, so cutting the internet off needs a tunnel. It is up
only while the parent has paused the internet and is torn down on resume. If the client would
rather drop Pause than keep any VPN use, that is a one-line scope decision.

## 2. DNS-based filtering, as KAHF Guard does it

We checked how KAHF Guard actually works rather than assuming:

- **No VPN.** KAHF's own FAQ says "No VPN used": the app points the device's DNS at KAHF's
  servers and filters lookups only.
- **Its resolver** for Android Private DNS is `high.kahfguard.com` (published in KAHF's blog). We
  connected to it over DNS-over-TLS, the protocol Private DNS uses, and measured it alongside the
  others below.
- **Custom blocked sites and URLs** are read from browsers' address bars through the Accessibility
  Service. KAHF's FAQ says it checks "view IDs and URLs in real time" across 17 browsers, and an
  independent analysis of its APK found 18 browser packages.

AMAN now uses the same design. Categories are refused at the resolver for every app. The parent's
own lists, and the bypass checks in section 4, run on the device in browsers' address bars, also
through Accessibility. Neither product can apply a single family's own list in non-browser apps
through a public resolver. That needs a resolver we operate, which is the Phase 1 backend work.

## 3. Coverage, measured

### Method

Coverage is often measured against a public category list such as UT1, but that is circular here:
the resolvers build their blocklists *from* those lists. Against UT1, KAHF and AdGuard both scored
about 99%, including on entries that are not adult at all. So we measured instead against
**popular sites whose own names are explicitly adult**. These were drawn from the Tranco top
1,000,000 list of most-visited domains, so they come from no blocklist, and dead domains were
removed. We also measured over-blocking against the top 1,000 sites that are not adult, and
against the most-visited sites in Arab country domains.

### Results

| Resolver | Popular adult sites (575) | Arabic adult sites (99) | Ordinary top-1,000 sites refused | Ordinary Arab-country sites refused (260) |
|---|---|---|---|---|
| **CleanBrowsing Family** (current default) | 67.8% | **36.4%** | 2.6% | 2.7% — incl. **men.gov.ma, tax.gov.ma, justice.gov.ma**, lematin.ma |
| Cloudflare for Families | 92.5% | **98.0%** | 0.1% | 0.4% |
| AdGuard Family | 93.2% | 89.9% | 7.8% — almost all ad/tracker hosts | 0.4% |
| KAHF Guard (`high`) | 88.9% | 63.6% | 10.9% — ad/tracker hosts, plus Roblox, Epic Games, Steam, Poki, character.ai | 1.9% — link shorteners |
| AMAN's on-device list (browsers only) | 36.9% | 17.2% | 0% | 0% |

The Arabic sample is small (99 live sites), so treat those figures as ±10 points.

### Ways round a resolver: measured by name

| Tested (10 each) | CleanBrowsing | KAHF Guard | Cloudflare | AdGuard |
|---|---|---|---|---|
| Web proxy sites (croxyproxy, proxysite, kproxy…) | 10 refused | 10 refused | 0 | 0 |
| Other DNS services a browser can be pointed at (dns.google, cloudflare-dns.com, quad9, NextDNS…) | 9 of 10 refused (not its own `doh.cleanbrowsing.org`) | 10 refused | 0 | 0 |
| VPN providers' sites (NordVPN, Proton, Psiphon…) | 10 refused | 10 refused | 0 | 0 |
| SafeSearch forced on Google and Bing | Yes | Yes | Yes | Yes |
| YouTube Restricted Mode | Moderate | Strict | **No** | Moderate |
| Ad and tracker hosts refused in every app | No | Yes | No | Yes |

### What this means

- **No public resolver does everything.** The two that close the bypass routes by name
  (CleanBrowsing, KAHF) are the two weakest on Arabic sites. The two with the widest adult and
  Arabic coverage (Cloudflare, AdGuard) leave every bypass route open.
- **The current default is the wrong choice for an Arabic-market product.** It misses about two
  thirds of popular Arabic adult sites, and it refuses Moroccan government sites, including the
  Ministry of Education. Those would be the first complaints from Moroccan parents.
- **KAHF's resolver is not the coverage benchmark its marketing suggests.** It claims "7.3M+"
  blocked sites, which cannot be checked from outside. Measured, it is behind Cloudflare and
  AdGuard on adult sites and well behind Cloudflare on Arabic ones. It is ahead on bypass routes,
  and it blocks popular games at its strong level, which a parent may or may not want.
  - KAHF also publishes an "IP block list". That list blocks abusive clients of KAHF's own
    resolver. It is not a defence against a child typing a site's IP address.
- **BlockerX** could not be verified. It publishes no resolver and no description of its
  mechanism, so its coverage can only be measured by installing it on a test phone. We can add
  that to the device test protocol if the client wants it.

### Arabic coverage on the device

For browsers' address bars, we added Arabic-market adult name patterns ("sexalarab", "arabxnxx",
Arabic-script words and others). Measured on the top million sites, they catch 31 popular Arabic
adult sites and refuse nothing else. Shorter fragments ("nik", "sks") were rejected because they
would block a Polish government office and a key server. This is a backstop for browsers, not a
substitute for resolver coverage.

### The parent can now choose

The Controls screen now offers all three public resolvers: CleanBrowsing Family, Cloudflare for
Families and the newly added AdGuard Family. Each shows what it covers, in the measured terms
above.

## 4. IP-address and other bypasses

A DNS filter judges names. Anything that reaches a site without looking up its name never reaches
the resolver, so these cases are now caught on the device. They are checked in the built-in
browser and in other browsers' address bars, and the browser is closed when one is refused:

| Bypass | What happens now |
|---|---|
| Typing a site's IP address (`http://66.254.114.41`) | **Refused.** Any public IP opened in a browser is refused, because ordinary browsing never uses one. |
| Disguised IP: one integer (`http://1123971625`), hex, octal, short forms | **Refused.** Each is decoded the way the browser decodes it. |
| `http://allowed-site.com@blocked-site.com` | **Refused.** The part before `@` is a user name, not the site. This also closed a hole in the parent's own block list. |
| IP-in-name services (`66-254-114-41.sslip.io`, `nip.io`) | **Refused.** Three of the four resolvers let these through. |
| IPv6 address in brackets | **Refused.** |
| Google Translate proxy (`www-site-com.translate.goog`), Google AMP cache, Wayback Machine | **Refused when the carried site would be refused.** Translating an ordinary page still works. |
| General web-proxy sites | Depends on the resolver: CleanBrowsing and KAHF refuse them, Cloudflare and AdGuard do not. |
| Browser set to its own secure DNS | Depends on the resolver: CleanBrowsing and KAHF refuse the DNS services' names. A DNS service entered by IP address (`https://1.1.1.1/dns-query`) cannot be stopped by any resolver. |
| VPN apps | Unchanged from the POC: VPN connection requests are cancelled, VPN apps are closed, and the phone is locked to Settings while a foreign VPN is up. |

**Normal use is protected by design.** Private network addresses (a home router at 192.168.1.1,
printers, a school intranet) are never refused. A number typed in a search box is not treated as
an address: "3.5" is a search, not the IP 3.0.0.5. A parent can allow a specific IP by hand.

**Limits we state plainly:**

- An app other than a browser that connects to an IP address built into it is invisible to every
  DNS filter, KAHF's included. Seeing it needs a VPN tunnel, which point 1 rules out.
- A second phone using this phone's hotspot is not filtered. Device Owner mode can disable
  tethering.
- Changing Private DNS in Settings is detected and alerted, not prevented, unless the phone is
  enrolled as Device Owner, where AMAN sets and locks it.

## Decisions needed

1. **Default resolver for the POC builds.** Our recommendation is **Cloudflare for Families** if
   Arabic coverage is the priority: best Arabic and adult coverage, almost no over-blocking. Its
   cost is that the DNS-service and proxy routes stay open and YouTube is not restricted.
   **CleanBrowsing** is the reverse. We have kept CleanBrowsing as the default until the client
   decides.
2. **AMAN's own resolver in Phase 1.** This is what removes the trade-off. It would combine a
   wide adult feed with Arabic additions, refuse proxies, VPN sites and DNS services by name,
   enforce SafeSearch and YouTube Restricted Mode, and hold each family's own lists for every app.
   It is the architecture KAHF runs, and the only way to beat the table above rather than pick a
   row of it.
3. **Internet Pause**: keep it (a VPN only while paused), or drop it to have no VPN use at all.

## Repeating the measurement

The scripts, the domain samples and the raw results are in
[coverage-measurement/](coverage-measurement/). They need Python 3 and nothing else.

```bash
cd docs/sprint0/coverage-measurement
python3 -I measure.py . ../../../app/src/main/assets/blocklists/adult-hosts.txt   # coverage tables
python3 -I bypass_hosts.py                                                       # bypass-route table
```

The scripts query each resolver over DNS-over-TLS on port 853, the same way Android Private DNS
does, and detect each resolver's own way of refusing a name. CleanBrowsing answers "no such
domain", Cloudflare answers `0.0.0.0`, AdGuard answers its block-page address, and KAHF answers
with no address. A domain is only counted if an unfiltered resolver (1.1.1.1) still resolves it.
Results can vary by a few points between runs as resolvers update their lists.
