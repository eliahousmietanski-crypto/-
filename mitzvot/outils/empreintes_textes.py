#!/usr/bin/env python3
"""Met l'empreinte du contenu dans le nom de chaque fichier de textes (textes/s00.1a2b3c4d.json)
et met à jour la table TX_META.files de index.html. À relancer après toute modification d'un texte.
Usage : python3 mitzvot/outils/empreintes_textes.py"""
import hashlib, json, os, re
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
tdir, page = os.path.join(root, 'textes'), os.path.join(root, 'index.html')
files = {}
for f in sorted(os.listdir(tdir)):
    m = re.fullmatch(r'([sh]\d\d)(?:\.[0-9a-f]{8})?\.json', f)
    if not m: continue
    data = open(os.path.join(tdir, f), 'rb').read()
    name = f'{m.group(1)}.{hashlib.sha256(data).hexdigest()[:8]}.json'
    if name != f: os.rename(os.path.join(tdir, f), os.path.join(tdir, name))
    files[m.group(1)] = name
s = open(page, encoding='utf-8').read()
m = re.search(r'^const TX_META = (.*);$', s, re.M)
meta = json.loads(m.group(1)); meta['files'] = files
s = s[:m.start()] + 'const TX_META = ' + json.dumps(meta, ensure_ascii=False, separators=(',', ':')) + ';' + s[m.end():]
open(page, 'w', encoding='utf-8').write(s)
print(len(files), 'fichiers de textes')
