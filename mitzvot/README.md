# Louah HaMitsvot

Calendrier des 613 mitsvot (Rambam) et des mitsvot de nos Sages pour chaque jour de l'année : mitsvot du jour, horaires, paracha, références du Choul'han Aroukh (avec traduction), Michné Torah et Séfer Ha'Hinoukh.

- `index.html` : l'application (calcul et interface), écrite pour être publiée telle quelle comme page Claude.
- `textes/` : les textes du Choul'han Aroukh, du Michné Torah et du Séfer Ha'Hinoukh avec leur traduction, découpés en petits fichiers chargés à la demande (`sNN.json` : références et Rambam ; `hNN.json` : Hinoukh). L'index `TX_META` dans `index.html` indique le fichier de chaque mitsva.
- `tests/verifier.js` : vérifie que, chaque jour et pour chaque réglage, les 613 mitsvot sont toutes classées (`node mitzvot/tests/verifier.js`).
- `pwa/` : la version installable et utilisable hors connexion (manifest, service worker, icônes) et `build.sh`, qui l'assemble dans `_site/`.
- `.github/workflows/mitzvot-pages.yml` : publie la version installable sur GitHub Pages.

## Mettre en ligne sur GitHub Pages

1. Dans le dépôt GitHub : **Settings → Pages → Build and deployment → Source : GitHub Actions**.
2. Dans **Settings → Environments → github-pages**, autoriser la branche de travail (ou fusionner sur la branche par défaut).
3. Relancer le workflow « Louah HaMitsvot (GitHub Pages) » depuis l'onglet **Actions**.
4. Ouvrir l'adresse affichée sur le téléphone, puis « Ajouter à l'écran d'accueil ».

Textes hébreux : Sefaria (Wikisource, domaine public / CC-BY-SA). L'application ne remplace pas l'avis d'un rav.
