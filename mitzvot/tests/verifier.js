// Vérifie le calcul : pour chaque jour, chaque réglage et plusieurs années, les 613 mitsvot sont toutes
// classées une fois (248 positives, 365 interdits), et chaque mitsva ayant des textes a son fichier.
// Usage : node mitzvot/tests/verifier.js
const fs = require("fs"), path = require("path"), vm = require("vm");
const dir = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(dir, "index.html"), "utf8");
const js = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).find(c => c.includes("const M613"));   // script principal
const ctx = { Intl, Date, Math, console, Object, JSON, Map, Set, String };
vm.createContext(ctx);
vm.runInContext(js.replace(/if \(typeof document[\s\S]*$/, "") + ";this.buildYear=buildYear;this.buckets=buckets;this.MZ=MZ;this.TX_META=TX_META;", ctx);
let bad = 0, n = 0;
for (const Y of [2026, 2027, 2028, 2029])
  for (const profile of ["h", "f", "k"]) for (const israel of [false, true]) for (const minhag of ["s", "a"]) {
    const o = { israel, profile, minhag };
    for (const x of ctx.buildYear(Y, o)) {
      const B = ctx.buckets(x, o), tot = { A: 0, L: 0 }; n++;
      for (const k of Object.keys(B)) for (const i of B[k]) { const m = ctx.MZ[i.id]; if (m.t === "R" || (k === "spec" && (i.susp || i.rab))) continue; tot[m.t]++; }
      if (tot.A !== 248 || tot.L !== 365) { if (++bad < 5) console.log("Écart", Y, JSON.stringify(o), x.dt.toISOString().slice(0, 10), tot); }
    }
  }
const files = new Set(fs.readdirSync(path.join(dir, "textes")));
// chaque mitsva a son fichier de références, présent sur le disque, avec la bonne empreinte
const crypto = require("crypto");
const sha = f => crypto.createHash("sha256").update(fs.readFileSync(path.join(dir, "textes", f))).digest("hex").slice(0, 8);
const missing = Object.entries(ctx.TX_META.chunk).filter(([, c]) => { const f = ctx.TX_META.files["s" + String(c).padStart(2, "0")]; return !f || !files.has(f); }).map(([id]) => id);
for (const f of Object.values(ctx.TX_META.files)) if (!files.has(f) || f.split(".")[1] !== sha(f)) { console.log("Empreinte fausse ou fichier absent :", f); missing.push(f); }
console.log(`${n} jours vérifiés, ${bad} écart(s) ; ${missing.length} mitsva(s) sans fichier de textes`);
process.exit(bad || missing.length ? 1 : 0);
