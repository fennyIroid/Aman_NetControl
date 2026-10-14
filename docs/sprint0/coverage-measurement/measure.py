"""Measures which filtering resolvers refuse which domains, over DoT as Android Private DNS does.

Usage: python3 -I measure.py <workdir> <bundled adult-hosts.txt>
Reads <workdir>/samples/*.txt, writes <workdir>/results.json.
"""
import concurrent.futures as cf
import json
import os
import random
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dnsq  # noqa: E402

RESOLVERS = {
    # KAHF refuses with an empty answer for most lists and 0.0.0.0 for some; both are blocks.
    "KAHF Guard (high)": ("high.kahfguard.com", lambda r: r[0] == 0 and (not r[1] or "0.0.0.0" in r[1])),
    "CleanBrowsing Family": ("family-filter-dns.cleanbrowsing.org", lambda r: r[0] == 3),
    "Cloudflare Families": ("family.cloudflare-dns.com", lambda r: "0.0.0.0" in r[1]),
    "AdGuard Family": ("family.adguard-dns.com", lambda r: "94.140.14.35" in r[1] or "0.0.0.0" in r[1]),
}
CONTROL = "1.1.1.1"


CONTROL_ANSWERS = {}


def alive(domains):
    """Keeps a domain only if the unfiltered control resolver returns an IPv4 address for it.

    This is what makes NXDOMAIN usable as CleanBrowsing's block signal: a dead domain is gone
    before any filtering resolver is asked, so NXDOMAIN on what is left is a refusal.
    """
    def check(d):
        r = dnsq.udp(CONTROL, d) or dnsq.udp(CONTROL, d)
        CONTROL_ANSWERS[d] = r
        return d if r and r[0] == 0 and r[1] else None
    with cf.ThreadPoolExecutor(32) as ex:
        return [d for d in ex.map(check, domains) if d]


def fmt(r):
    if r is None:
        return "timeout"
    return {0: "NOERROR", 2: "SERVFAIL", 3: "NXDOMAIN", 5: "REFUSED"}.get(r[0], f"rcode{r[0]}") + \
        (" " + ",".join(r[1]) if r[1] else (" (no address)" if r[0] == 0 else ""))


def indeterminate(r):
    """SERVFAIL, REFUSED or no answer at all says nothing about filtering.

    Measured: CleanBrowsing answered SERVFAIL for apple.com, youtube.com and android.com under
    parallel load. Counting that as "passed" would under-score a resolver on adult sites, and
    counting it as "blocked" would invent false positives, so it is retried and, if it persists,
    left out of the denominator.
    """
    return r is None or r[0] in (2, 5)


def run_resolver(host, domains, threads=6):
    chunks = [domains[i::threads] for i in range(threads)]

    def work(chunk):
        c = dnsq.DoT(host)
        return {d: c.query(d) for d in chunk}
    out = {}
    with cf.ThreadPoolExecutor(threads) as ex:
        for part in ex.map(work, chunks):
            out.update(part)
    for _ in range(2):
        retry = [d for d, r in out.items() if indeterminate(r)]
        if not retry:
            break
        time.sleep(5)
        c = dnsq.DoT(host)
        for d in retry:
            out[d] = c.query(d)
            time.sleep(0.2)
    return out


def load_bundled(path):
    hosts = set()
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            hosts.add(line.split()[-1].lower())
    return hosts


def bundled_blocks(hosts, d):
    parts = d.split(".")
    return any(".".join(parts[i:]) in hosts for i in range(len(parts) - 1))


def main():
    work, bundled_path = sys.argv[1], sys.argv[2]
    random.seed(11)
    bundled = load_bundled(bundled_path)
    samples = {}
    for name in sorted(os.listdir(os.path.join(work, "samples"))):
        doms = [l.strip() for l in open(os.path.join(work, "samples", name)) if l.strip()]
        samples[name[:-4]] = alive(doms)
        print(name, len(doms), "->", len(samples[name[:-4]]), "alive", flush=True)

    every = sorted({d for v in samples.values() for d in v})
    raw = {}
    for label, (host, _) in RESOLVERS.items():
        raw[label] = run_resolver(host, every)
        print(label, "done", flush=True)

    results = {}
    for s, doms in samples.items():
        row = {"n": len(doms)}
        for label, (_, blocked) in RESOLVERS.items():
            answers = [raw[label].get(d) for d in doms]
            ok = [(d, a) for d, a in zip(doms, answers) if not indeterminate(a)]
            hit = [d for d, a in ok if blocked(a)]
            row[label] = {"answered": len(ok), "blocked": len(hit), "examples_blocked": hit[:8],
                          "examples_passed": [d for d, a in ok if not blocked(a)][:8]}
        hit = [d for d in doms if bundled_blocks(bundled, d)]
        row["AMAN bundled list"] = {"answered": len(doms), "blocked": len(hit),
                                    "examples_blocked": hit[:8],
                                    "examples_passed": [d for d in doms if d not in hit][:8]}
        results[s] = row
    json.dump(results, open(os.path.join(work, "results.json"), "w"), indent=1)

    # One row per domain per resolver: what the control said, what the resolver said, and the
    # verdict read from it. Dead domains are listed too, so it is visible what was dropped.
    with open(os.path.join(work, "results-raw.tsv"), "w") as out:
        out.write("sample\tdomain\tcontrol_1.1.1.1\tresolver\tanswer\tverdict\n")
        for name in sorted(os.listdir(os.path.join(work, "samples"))):
            s = name[:-4]
            for d in [l.strip() for l in open(os.path.join(work, "samples", name)) if l.strip()]:
                if d not in samples[s]:
                    out.write(f"{s}\t{d}\t{fmt(CONTROL_ANSWERS.get(d))}\t-\t-\tdropped (dead)\n")
                    continue
                for label, (_, blocked) in RESOLVERS.items():
                    a = raw[label].get(d)
                    v = "unresolved" if indeterminate(a) else ("blocked" if blocked(a) else "passed")
                    out.write(f"{s}\t{d}\t{fmt(CONTROL_ANSWERS[d])}\t{label}\t{fmt(a)}\t{v}\n")
                v = "blocked" if bundled_blocks(bundled, d) else "passed"
                out.write(f"{s}\t{d}\t{fmt(CONTROL_ANSWERS[d])}\tAMAN bundled list\t-\t{v}\n")
    for s, row in results.items():
        print(f"\n## {s} (n={row['n']})")
        for k, v in row.items():
            if k == "n":
                continue
            pct = 100 * v["blocked"] / v["answered"] if v["answered"] else 0
            print(f"  {k:24s} {v['blocked']:5d}/{v['answered']:<5d} {pct:5.1f}%  passed e.g. {v['examples_passed'][:4]}")


if __name__ == "__main__":
    main()
