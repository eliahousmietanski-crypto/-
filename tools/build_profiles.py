#!/usr/bin/env python3
"""Génère app/src/main/assets/profiles.txt à partir de Flipper-IRDB.

Flipper-IRDB (https://github.com/Lucaslhm/Flipper-IRDB) est une base publique de
codes infrarouges sous licence CC0 1.0. On en extrait les télécommandes de barres
de son et d'enceintes, on associe leurs touches aux 12 touches de la télécommande
de l'Aiwa ASB-060, et on les classe par ressemblance avec celle-ci.

Usage : python3 tools/build_profiles.py /chemin/vers/Flipper-IRDB
"""
import collections
import os
import re
import sys

# Les 12 touches de la télécommande d'origine de l'ASB-060.
KEYS = ["power", "mode", "volup", "voldown", "prev", "play", "next",
        "bassup", "bassdown", "trebleup", "trebledown", "eq"]

UP = r"(up|\+|plus|inc|increase)"
DOWN = r"(dn|down|dwn|-|minus|dec|decrease)"

# Pour chaque fonction : expressions régulières (nom complet), de la plus sûre à la moins sûre.
RULES = {
    "power": [r"power|pwr|on_?off|power_?toggle|standby|power_on_?off"],
    "mode": [r"mode|source|input|src|func|function",
             r"input_?source|change_input|input_choice|input_src|inputnext|source_select"],
    "volup": [r"(vol|volume)_?" + UP, r"(v|main|master)_?" + UP],
    "voldown": [r"(vol|volume)_?" + DOWN, r"(v|main|master)_?" + DOWN],
    "mute": [r"mute|volume_?mute|vol_?mute|silence"],
    "play": [r"play_?pause|pause_?play|play_pa|sel_play_pause|play_pause_pair|pause_mute|play_pause_enter|bt_play_pause",
             r"play", r"pause", r"play_stop"],
    "prev": [r"prev|previous|prev_?track|prev_trck|track_back|back_track|skip_?back|left_previous|bt_back",
             r"reverse|backward|bkwd|back", r"rewind|fast_rewind"],
    "next": [r"next|next_?track|next_trck|track_fwd|skip_?forward|right_next|bt_next",
             r"forward|foward|fwd|frwd|skip", r"fast_?forward|ff|ffw"],
    "bassup": [r"bass_?(vol_)?" + UP, r"(sub|subwoofer|subwoof|subwf|woofer|woof|sw)_?" + UP],
    "bassdown": [r"bass?_?(vol_)?" + DOWN, r"(sub|subwoofer|subwoof|subwf|woofer|woof|sw)_?" + DOWN],
    "trebleup": [r"(treble|treb|trebble|trbl|tremble)_?" + UP],
    "trebledown": [r"(treble|treb|trebble|trbl|tremble)_?" + DOWN],
    "eq": [r"eq|equali[sz]er|preset_eq", r"sound_?mode|sound_?eff?ect|sfx|sound|effect|dsp"],
}

# Télécommande vérifiée identique à celle de l'ASB-060 : même forme, mêmes 12 touches aux mêmes
# endroits (photo de la fiche produit de l'ASB-060 comparée au mode d'emploi de la Majority K2).
TWIN = "Majority K2 SoundBar"

CATEGORY_WEIGHT = {"SoundBars": 1.0, "Speakers": 0.85}
OTHER_CATEGORIES = ["Audio_and_Video_Receivers", "Multimedia", "Miscellaneous", "Head_Units", "CD_Players"]
OTHER_WEIGHT = 0.7
OTHER_MIN_KEYS = 8


def norm(name):
    n = name.lower().strip()
    n = re.sub(r"[\s/|.]+", "_", n)
    n = re.sub(r"_*([+\-])_*$", r"\1", n)      # "bass +" -> "bass+"
    n = re.sub(r"(?<=[a-z])-(?=[a-z])", "_", n)  # "play-pause" -> "play_pause"
    return n.strip("_")


def parse_ir(path):
    sigs, cur = [], None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if line.startswith("#") or ":" not in line:
                continue
            k, v = line.split(":", 1)
            k, v = k.strip(), v.strip()
            if k == "name":
                cur = {"name": v}
                sigs.append(cur)
            elif cur is not None:
                cur[k] = v
    return sigs


def hexbytes(s):
    return [int(x, 16) for x in s.split()]


def to_code(sig):
    """Convertit un signal Flipper vers le format texte de l'appli, ou None."""
    try:
        if sig.get("type") == "parsed":
            proto = sig.get("protocol")
            a = hexbytes(sig["address"])
            c = hexbytes(sig["command"])
            if proto == "NEC":
                return "NEC:%02X:%02X" % (a[0], c[0])
            if proto == "NECext":
                a0, a1, c0, c1 = a[0], a[1], c[0], c[1]
                addr = "%02X" % a0 if a1 == (a0 ^ 0xFF) else "%02X%02X" % (a1, a0)
                cmd = "%02X" % c0 if c1 == (c0 ^ 0xFF) else "%02X%02X" % (c1, c0)
                return "NEC:%s:%s" % (addr, cmd)
            return None
        if sig.get("type") == "raw":
            freq = int(float(sig.get("frequency", "38000")))
            data = [int(x) for x in sig["data"].split()]
            if not (8 <= len(data) <= 200) or not (30000 <= freq <= 60000):
                return None
            if sum(data) > 1_500_000 or min(data) <= 0:
                return None
            return "RAW:%d:%s" % (freq, ",".join(str(d) for d in data))
    except (KeyError, ValueError, IndexError):
        return None
    return None


def map_keys(sigs):
    """Associe les signaux d'un fichier aux fonctions connues."""
    named = [(norm(s["name"]), s) for s in sigs]
    found = {}
    for func, patterns in RULES.items():
        for pat in patterns:
            hit = next((s for n, s in named if re.fullmatch(pat, n) and to_code(s)), None)
            if hit is not None:
                found[func] = to_code(hit)
                break
    # "+" / "-" seuls : volume sur les télécommandes très simples.
    if "volup" not in found and "voldown" not in found:
        for n, s in named:
            if n in ("+", "plus") and to_code(s):
                found.setdefault("volup", to_code(s))
            if n in ("-", "minus") and to_code(s):
                found.setdefault("voldown", to_code(s))
    return found


def pretty(path):
    name = os.path.splitext(os.path.basename(path))[0]
    name = re.sub(r"[_]+", " ", name).strip(" .")
    return re.sub(r"\s+", " ", name)


def collect(root, category, weight, min_keys):
    out = []
    base = os.path.join(root, category)
    for dp, _, files in sorted(os.walk(base)):
        for f in sorted(files):
            if not f.lower().endswith(".ir"):
                continue
            path = os.path.join(dp, f)
            sigs = parse_ir(path)
            if not sigs:
                continue
            keys = map_keys(sigs)
            matched = sum(1 for k in KEYS if k in keys)
            if matched < min_keys:
                continue
            # Nombre de touches réellement différentes (certains fichiers répètent un signal).
            total = len({(s.get("protocol"), s.get("address"), s.get("command"), s.get("data")) for s in sigs})
            extra = max(0, total - len(keys))
            # Les codes « bruts » (protocole non reconnu, souvent de grandes marques) comptent moins.
            raw = sum(1 for k in KEYS if keys.get(k, "").startswith("RAW"))
            weight_raw = 1 - 0.5 * raw / matched
            score = min(95, round(100 * weight * weight_raw * matched / (len(KEYS) + extra)))
            if pretty(path) == TWIN:
                score = 100
            out.append({"cat": category, "name": pretty(path), "keys": keys,
                        "matched": matched, "total": total, "score": score})
    return out


def address_stats(root, categories):
    """Adresses NEC les plus fréquentes, et commandes connues pour chacune."""
    files_per_addr = collections.Counter()
    cmds = collections.defaultdict(collections.Counter)
    for cat in categories:
        for dp, _, files in os.walk(os.path.join(root, cat)):
            for f in files:
                if not f.lower().endswith(".ir"):
                    continue
                seen = set()
                for s in parse_ir(os.path.join(dp, f)):
                    code = to_code(s) if s.get("type") == "parsed" else None
                    if not code:
                        continue
                    _, addr, cmd = code.split(":")
                    seen.add(addr)
                    if len(cmd) == 2:
                        cmds[addr][cmd] += 1
                for a in seen:
                    files_per_addr[a] += 1
    return files_per_addr, cmds


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = sys.argv[1]
    profiles = []
    for cat, w in CATEGORY_WEIGHT.items():
        profiles += collect(root, cat, w, 3)
    for cat in OTHER_CATEGORIES:
        profiles += collect(root, cat, OTHER_WEIGHT, OTHER_MIN_KEYS)

    # Fusionne les télécommandes aux codes identiques.
    uniq = {}
    for p in sorted(profiles, key=lambda p: (-p["score"], p["total"], p["name"])):
        sig = tuple(sorted(p["keys"].items()))
        if sig not in uniq:
            uniq[sig] = p
    profiles = list(uniq.values())

    similar, _ = address_stats(root, list(CATEGORY_WEIGHT))
    audio, cmds = address_stats(root, list(CATEGORY_WEIGHT) + OTHER_CATEGORIES)
    order = [a for a, _ in similar.most_common()]
    order += [a for a, _ in audio.most_common() if a not in similar]

    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "profiles.txt")
    with open(dest, "w", encoding="utf-8") as out:
        out.write("# Généré par tools/build_profiles.py — ne pas modifier à la main.\n")
        out.write("# Source : Flipper-IRDB (CC0 1.0), https://github.com/Lucaslhm/Flipper-IRDB\n")
        out.write("# P|catégorie|nom|ressemblance (0-100)|touches reconnues|touches au total|T si télécommande identique\n")
        out.write("# K|fonction|code\n")
        out.write("# A|adresse NEC|nombre de télécommandes|commandes connues\n")
        for p in profiles:
            out.write("P|%s|%s|%d|%d|%d|%s\n" % (p["cat"], p["name"].replace("|", "/"), p["score"], p["matched"],
                                                 p["total"], "T" if p["name"] == TWIN else ""))
            for k in KEYS + ["mute"]:
                if k in p["keys"]:
                    out.write("K|%s|%s\n" % (k, p["keys"][k]))
        for a in order:
            known = ",".join(c for c, _ in cmds[a].most_common())
            if known:
                out.write("A|%s|%d|%s\n" % (a, audio[a], known))
    print("%d télécommandes, %d adresses -> %s" % (len(profiles), len(order), os.path.normpath(dest)))


if __name__ == "__main__":
    main()
