import os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import measure
work = sys.argv[1]
out = {}
for name in ["3_top1000_not_adult", "4_arab_country_top_not_adult"]:
    doms = [l.strip() for l in open(os.path.join(work, "samples", name + ".txt")) if l.strip()]
    doms = measure.alive(doms)
    for label, (host, blocked) in measure.RESOLVERS.items():
        res = measure.run_resolver(host, doms)
        hit = [d for d in doms if res.get(d) is not None and blocked(res[d])]
        out.setdefault(name, {})[label] = hit
        print(f"\n{name} / {label}: {len(hit)}\n  " + " ".join(hit))
json.dump(out, open(os.path.join(work, "overblock.json"), "w"), indent=1)
