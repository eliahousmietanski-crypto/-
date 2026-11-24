// Service worker de Louah HaMitsvot : l’application s’ouvre tout de suite depuis le téléphone, même hors connexion.
// build.sh remplace les trois valeurs ci-dessous à chaque construction.
const SHELL = "louah-app-__VERSION__";        // page, manifeste, icônes : changent à chaque version
const TEXTS = "louah-textes-__TX_VERSION__";  // textes : ne changent que si les textes changent
const FONTS = "louah-polices-1";
const CORE = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png", "./icon-180.png"];
const TEXT_FILES = __TX_FILES__;
const FONT_CSS = "https://fonts.googleapis.com/css2?family=Frank+Ruhl+Libre:wght@500;700;900&family=Assistant:wght@400;600;700&display=swap";

// Polices : on garde la feuille de style et ses fichiers, pour que l’hébreu s’affiche bien hors connexion.
async function cacheFonts() {
  const c = await caches.open(FONTS);
  if (await c.match(FONT_CSS)) return;
  const r = await fetch(FONT_CSS, { mode: "cors" });
  if (!r.ok) return;
  const css = await r.clone().text();
  await c.put(FONT_CSS, r);
  const urls = [...css.matchAll(/url\((https:\/\/fonts\.gstatic\.com[^)]+)\)/g)].map(m => m[1]);
  await Promise.all(urls.map(u => fetch(u, { mode: "cors" }).then(f => f.ok && c.put(u, f)).catch(() => {})));
}

// Textes : téléchargés en arrière-plan après l’installation, par petits groupes.
async function cacheTexts() {
  const c = await caches.open(TEXTS);
  for (let i = 0; i < TEXT_FILES.length; i += 6) {
    await Promise.all(TEXT_FILES.slice(i, i + 6).map(async f => {
      const u = new URL("textes/" + f, self.registration.scope).href;
      if (await c.match(u)) return;
      try { const r = await fetch(u); if (r.ok) await c.put(u, r); } catch (e) {}
    }));
  }
}

self.addEventListener("install", e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil((async () => {
    const keep = [SHELL, TEXTS, FONTS];
    for (const k of await caches.keys()) if (k.startsWith("louah") && !keep.includes(k)) await caches.delete(k);
    await self.clients.claim();
  })());
  // Sans bloquer l’ouverture : polices et textes.
  cacheFonts().catch(() => {}).then(cacheTexts).catch(() => {});
});

const fromCache = async (cacheName, req) => {
  const c = await caches.open(cacheName), hit = await c.match(req);
  if (hit) return hit;
  const r = await fetch(req);
  if (r.ok || r.type === "opaque") c.put(req, r.clone()).catch(() => {});
  return r;
};

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.hostname === "fonts.googleapis.com" || url.hostname === "fonts.gstatic.com") { e.respondWith(fromCache(FONTS, req)); return; }
  if (url.origin !== location.origin) return;
  if (url.pathname.includes("/textes/")) { e.respondWith(fromCache(TEXTS, req)); return; }
  // Page : depuis le téléphone, tout de suite. Une nouvelle version arrive avec le nouveau service worker.
  if (req.mode === "navigate") {
    e.respondWith(caches.open(SHELL).then(c => c.match("./index.html")).then(hit => hit || fetch(req)));
    return;
  }
  e.respondWith(fromCache(SHELL, req));
});
