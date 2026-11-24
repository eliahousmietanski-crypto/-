// Compare le calcul de l’application à @hebcal/core, la bibliothèque de référence du calendrier juif :
// dates hébraïques, fêtes, jeûnes, Roch ‘Hodech, ‘Hol HaMoed, paracha, Chabbatot particuliers et horaires.
// Usage : (cd mitzvot/tests && npm ci && TZ=UTC node hebcal.mjs)
import fs from "node:fs"; import path from "node:path"; import vm from "node:vm"; import { fileURLToPath } from "node:url";
import { HDate, HebrewCalendar, Zmanim, GeoLocation, flags } from "@hebcal/core";
if (process.env.TZ !== "UTC") { console.error("Lancer avec TZ=UTC"); process.exit(2); }
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const js = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).find(c => c.includes("const M613"));   // script principal
const ctx = { Intl, Date, Math, console, Object, JSON, Map, Set, String }; vm.createContext(ctx);
vm.runInContext(js.replace(/if \(typeof document[\s\S]*$/, "") + ";Object.assign(this,{buildYear,hebDate,parashaFor,PARSHIOT,zmanim,CITIES,HE_MONTH});", ctx);
const A = ctx; let fails = 0; const report = [];
const fail = (msg) => { if (++fails <= 40) console.log("ÉCART", msg); };
const iso = t => new Date(t).toISOString().slice(0, 10);

/* 1. Dates hébraïques, 1950-2100 */
const HM = { Tishrei: "TISHRI", Cheshvan: "HESHVAN", Kislev: "KISLEV", Tevet: "TEVET", "Sh'vat": "SHEVAT", Adar: "ADAR", "Adar I": "ADAR1", "Adar II": "ADAR2", Nisan: "NISAN", Iyyar: "IYAR", Sivan: "SIVAN", Tamuz: "TAMUZ", Av: "AV", Elul: "ELUL" };
let nd = 0;
for (let t = Date.UTC(1950, 0, 1); t <= Date.UTC(2100, 11, 31); t += 864e5) {
  const a = A.hebDate(new Date(t + 12 * 36e5)), h = new HDate(new Date(t));
  const m = HM[h.getMonthName()];
  if (a.d !== h.getDate() || a.m !== m || a.y !== h.getFullYear()) fail(`date ${iso(t)} : app ${a.d} ${a.m} ${a.y}, Hebcal ${h.getDate()} ${m} ${h.getFullYear()}`);
  nd++;
}
report.push(`Dates hébraïques : ${nd} jours comparés (1950-2100)`);

/* 2. Fêtes, jeûnes, Roch ‘Hodech, ‘Hol HaMoed, Pourim — 2024-2035, diaspora et Israël */
const Y0 = 2024, Y1 = 2035; let ne = 0;
for (const il of [false, true]) {
  const ref = new Map();   // date -> Set de catégories
  const add = (d, k) => { const s = iso(d.getTime()); if (!ref.has(s)) ref.set(s, new Set()); ref.get(s).add(k); };
  for (const ev of HebrewCalendar.calendar({ start: new Date(Y0, 0, 1), end: new Date(Y1, 11, 31), il, noModern: true, noSpecialShabbat: true, noRoshChodesh: false })) {
    const f = ev.getFlags(), d = ev.getDate().greg(), desc = ev.getDesc();
    if (f & flags.CHAG) add(d, "chag");
    if ((f & (flags.MAJOR_FAST | flags.MINOR_FAST)) && !/Bechorot|Katan|^Erev/.test(desc)) add(d, "jeûne");
    if (f & flags.ROSH_CHODESH) add(d, "roch-hodech");
    if (f & flags.CHOL_HAMOED) add(d, "hol-hamoed");
    if (desc === "Purim" || desc === "Shushan Purim") add(d, desc === "Purim" ? "pourim" : "chouchan-pourim");
  }
  for (let Y = Y0; Y <= Y1; Y++) for (const x of A.buildYear(Y, { israel: il, profile: "h", minhag: "s" })) {
    const s = new Set();
    for (const e of x.r.ev) {
      if (e.cls === "yt") s.add("chag");
      if (e.cls === "fast") s.add("jeûne");
      if (e.fr === "Roch ‘Hodech") s.add("roch-hodech");
      if (/Hol HaMoed|Hochaana/.test(e.fr)) s.add("hol-hamoed");
      if (e.fr === "Pourim") s.add("pourim");
      if (e.fr === "Chouchan Pourim") s.add("chouchan-pourim");
    }
    if (x.h.m === "TISHRI" && x.h.d === 10) s.add("jeûne");
    const r = ref.get(iso(x.t)) || new Set();
    const a = [...s].sort().join(","), b = [...r].sort().join(",");
    if (a !== b) fail(`${il ? "Israël" : "diaspora"} ${iso(x.t)} : app [${a}], Hebcal [${b}]`);
    ne++;
  }
}
report.push(`Fêtes, jeûnes, Roch ‘Hodech, ‘Hol HaMoed, Pourim : ${ne} jours comparés (${Y0}-${Y1}, diaspora et Israël)`);

/* 3. Paracha et Chabbatot particuliers, chaque Chabbat 2024-2035 */
const PN = ["Bereshit", "Noach", "Lech-Lecha", "Vayera", "Chayei Sara", "Toldot", "Vayetzei", "Vayishlach", "Vayeshev", "Miketz", "Vayigash", "Vayechi", "Shemot", "Vaera", "Bo", "Beshalach", "Yitro", "Mishpatim", "Terumah", "Tetzaveh", "Ki Tisa", "Vayakhel", "Pekudei", "Vayikra", "Tzav", "Shmini", "Tazria", "Metzora", "Achrei Mot", "Kedoshim", "Emor", "Behar", "Bechukotai", "Bamidbar", "Nasso", "Beha'alotcha", "Sh'lach", "Korach", "Chukat", "Balak", "Pinchas", "Matot", "Masei", "Devarim", "Vaetchanan", "Eikev", "Re'eh", "Shoftim", "Ki Teitzei", "Ki Tavo", "Nitzavim", "Vayeilech", "Ha'azinu", "Vezot Haberakhah"];
const SPEC = { "Shabbat Shekalim": "Chabbat Chekalim", "Shabbat Zachor": null, "Shabbat Parah": "Chabbat Para", "Shabbat HaChodesh": "Chabbat Ha‘hodech", "Shabbat HaGadol": "Chabbat Hagadol", "Shabbat Shuva": "Chabbat Chouva", "Shabbat Chazon": "Chabbat ‘Hazon", "Shabbat Nachamu": "Chabbat Na‘hamou" };
let np = 0;
for (const il of [false, true]) {
  const spec = new Map();
  for (const ev of HebrewCalendar.calendar({ start: new Date(Y0, 0, 1), end: new Date(Y1, 11, 31), il, noModern: true, noSpecialShabbat: false }))
    if (ev.getFlags() & flags.SPECIAL_SHABBAT) spec.set(iso(ev.getDate().greg().getTime()), ev.getDesc());
  for (let t = Date.UTC(Y0, 0, 6); t <= Date.UTC(Y1, 11, 31); t += 7 * 864e5) {
    const hd = new HDate(new Date(t)); if (hd.getDay() !== 6) throw new Error("pas un samedi");
    const ref = HebrewCalendar.getSedra(hd.getFullYear(), il).lookup(hd);
    const h = A.hebDate(new Date(t + 12 * 36e5)), P = A.parashaFor(t, h, il);
    const a = P.ids ? P.ids.map(i => PN[i]).join("-") : "fête";
    const b = ref.chag ? "fête" : ref.parsha.join("-");
    if (a !== b) fail(`paracha ${il ? "Israël" : "diaspora"} ${iso(t)} : app ${a}, Hebcal ${b}`);
    const sp = spec.get(iso(t)), want = sp ? SPEC[sp] : undefined;
    const got = P.named ? P.named.fr : undefined;
    if (want !== null && want !== got) fail(`Chabbat particulier ${il ? "Israël" : "diaspora"} ${iso(t)} : app ${got}, Hebcal ${sp}`);
    np++;
  }
}
report.push(`Paracha et Chabbatot particuliers : ${np} Chabbatot comparés (${Y0}-${Y1}, diaspora et Israël)`);

/* 4. Horaires : chaque ville de l’application, un jour sur cinq en 2026 et 2027 */
const Z = [["Aube 16,1°", "alot", z => z.alotHaShachar()], ["Lever", "netz", z => z.sunrise()], ["Fin du Chema MGA", "shemaMga", z => z.sofZmanShmaMGA()],
  ["Fin du Chema Gra", "shemaGra", z => z.sofZmanShma()], ["Fin de la ‘Amida", "tefila", z => z.sofZmanTfilla()], ["‘Hatsot", "chatzot", z => z.chatzot()],
  ["Min‘ha gedola", "minchaG", z => z.minchaGedola()], ["Plag hamin‘ha", "plag", z => z.plagHaMincha()], ["Coucher", "shkia", z => z.sunset()], ["Tsét 8,5°", "tzet", z => z.tzeit(8.5)]];
const stats = Object.fromEntries(Z.map(([l]) => [l, { max: 0, sum: 0, n: 0, where: "" }]));
for (const c of A.CITIES) {
  const gl = new GeoLocation(c.fr, c.lat, c.lon, 0, c.tz);
  for (let t = Date.UTC(2026, 0, 1); t <= Date.UTC(2027, 11, 31); t += 5 * 864e5) {
    const a = A.zmanim(t, c), z = new Zmanim(gl, new Date(t), false);
    for (const [l, k, f] of Z) {
      const r = f(z); if (!r || isNaN(r) || a[k] == null) continue;
      const d = Math.abs(a[k] - r.getTime()) / 6e4, s = stats[l];
      s.sum += d; s.n++; if (d > s.max) { s.max = d; s.where = `${c.fr} ${iso(t)}`; }
    }
  }
}
const TOL = 5 / 60;   // 5 secondes
for (const [l, s] of Object.entries(stats)) {
  report.push(`Horaires · ${l} : écart moyen ${(s.sum / s.n * 60).toFixed(0)} s, maximum ${(s.max * 60).toFixed(0)} s (${s.where})`);
  if (s.max > TOL) fail(`horaire ${l} : écart maximum ${s.max.toFixed(2)} min (${s.where})`);
}
console.log(report.join("\n"));
console.log(fails ? `${fails} écart(s) avec Hebcal` : "Aucun écart avec Hebcal");
process.exit(fails ? 1 : 0);
