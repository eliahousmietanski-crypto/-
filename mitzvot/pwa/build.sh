#!/usr/bin/env bash
# Construit la version installable (GitHub Pages) de Louah HaMitsvot dans le dossier donné (par défaut _site).
# La page mitzvot/index.html est écrite pour Claude (sans <head>) : on l'enveloppe ici dans un document complet.
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-../../_site}"
mkdir -p "$OUT"
{
  printf '%s\n' '<!doctype html>' '<html lang="fr">' '<head>' \
    '<meta charset="utf-8">' \
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">' \
    '<meta name="theme-color" content="#1d4ea6">' \
    '<link rel="manifest" href="manifest.webmanifest">' \
    '<link rel="icon" href="icon-192.png">' \
    '<link rel="apple-touch-icon" href="icon-180.png">' \
    '<meta name="apple-mobile-web-app-capable" content="yes">' \
    '<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>' \
    '</head>' '<body>'
  cat ../index.html
  printf '%s\n' '<script>if ("serviceWorker" in navigator) { window.addEventListener("load", () => navigator.serviceWorker.register("sw.js").catch(() => {})); }</script>' \
    '</body>' '</html>'
} > "$OUT/index.html"
cp manifest.webmanifest sw.js icon-180.png icon-192.png icon-512.png "$OUT/"
echo "Site construit dans $OUT"
