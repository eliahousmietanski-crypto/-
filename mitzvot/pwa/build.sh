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
    '<meta name="description" content="Les 613 mitsvot pour chaque jour de l’année, avec le Choul‘han Aroukh, le Michné Torah et le Séfer Ha‘Hinoukh traduits.">' \
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
cp manifest.webmanifest icon-180.png icon-192.png icon-512.png "$OUT/"
# Textes chargés à la demande
rm -rf "$OUT/textes" && cp -r ../textes "$OUT/textes"
# Service worker : numéros de version tirés du contenu, et liste des fichiers de textes
VERSION=$(cat "$OUT/index.html" manifest.webmanifest | sha256sum | cut -c1-12)
TX_VERSION=$(cat ../textes/*.json | sha256sum | cut -c1-12)
TX_FILES=$(cd ../textes && ls *.json | sed 's/.*/"&"/' | paste -sd, -)
sed -e "s/__VERSION__/$VERSION/" -e "s/__TX_VERSION__/$TX_VERSION/" -e "s/__TX_FILES__/[$TX_FILES]/" sw.js > "$OUT/sw.js"
echo "Site construit dans $OUT"
