import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dnsq
R={"KAHF":("high.kahfguard.com",lambda r:r[0]==0 and (not r[1] or '0.0.0.0' in r[1])),
   "CleanBrowsing":("family-filter-dns.cleanbrowsing.org",lambda r:r[0]==3),
   "Cloudflare":("family.cloudflare-dns.com",lambda r:'0.0.0.0' in r[1]),
   "AdGuard":("family.adguard-dns.com",lambda r:'94.140.14.35' in r[1] or '0.0.0.0' in r[1])}
groups={
 "web proxy":["croxyproxy.com","www.croxyproxy.com","proxysite.com","www.proxysite.com","kproxy.com","hidester.com","4everproxy.com","proxyium.com","blockaway.net","hide.me"],
 "browser DoH":["dns.google","cloudflare-dns.com","mozilla.cloudflare-dns.com","chrome.cloudflare-dns.com","dns.quad9.net","doh.opendns.com","dns.nextdns.io","dns.adguard-dns.com","doh.cleanbrowsing.org","dns11.quad9.net"],
 "VPN sites":["nordvpn.com","expressvpn.com","protonvpn.com","surfshark.com","windscribe.com","psiphon.ca","hotspotshield.com","tunnelbear.com","one.one.one.one","urban-vpn.com"],
 "IP-in-name":["66-254-114-41.sslip.io","66.254.114.41.nip.io"],
 "relays":["translate.goog","www-pornhub-com.translate.goog","web.archive.org"],
 "search":["www.google.com","www.bing.com","duckduckgo.com","www.youtube.com"],
}
cl={k:dnsq.DoT(h) for k,(h,_) in R.items()}
print(f"{'':34s}"+"".join(f"{k:>16s}" for k in R))
for g,ds in groups.items():
    print("--",g)
    for d in ds:
        row=[]
        for k,(h,b) in R.items():
            r=cl[k].query(d)
            row.append("ERR" if r is None else ("BLOCK" if b(r) else (",".join(r[1][:1]) or f"rc{r[0]}")))
        print(f"{d:34s}"+"".join(f"{x:>16s}" for x in row))
