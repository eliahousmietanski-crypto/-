# Télécommande Aiwa ASB-060 pour Xiaomi Poco X5 5G

Appli Android qui remplace la télécommande d'origine de la barre de son **Aiwa ASB-060** (barre 2.1 de 330 W avec caisson sans fil). Elle passe par l'**émetteur infrarouge** du Poco X5 5G, sur le bord supérieur du téléphone.

L'écran reprend la télécommande d'origine : les mêmes 12 touches, aux mêmes endroits.

| Touche | Rôle |
|---|---|
| Power | Marche / arrêt |
| MODE | Change de source (Bluetooth, HDMI ARC, optique, AUX, USB) |
| Couronne | Volume + en haut, volume − en bas, piste précédente et suivante sur les côtés, lecture/pause au centre |
| BASS ▲ ▼ | Basses |
| TREBLE ▲ ▼ | Aigus |
| EQ | Mode sonore (sur la Majority K2, le garder appuyé allume ou éteint la lumière) |

Comme sur la vraie télécommande, garder le doigt sur une touche répète le code.

## Installation

1. Récupérez `app-release.apk` : onglet **Actions** du dépôt, dernier build, artefact *aiwa-asb060-remote*.
2. Ouvrez l'APK sur le téléphone et autorisez « Installer des applis inconnues ».
3. Chaque nouvelle version s'installe par-dessus la précédente, sans perdre les codes enregistrés.

## D'où viennent les codes

Il n'existe pas de liste publique des codes de l'ASB-060, et le Poco X5 ne peut pas lire une télécommande (il émet de l'infrarouge, mais n'en reçoit pas).

En revanche, la télécommande de l'ASB-060 est **identique à celle de la barre de son Majority K2** : même boîtier, mêmes 12 touches aux mêmes endroits (photo de la fiche produit de l'ASB-060 comparée au mode d'emploi de la K2). Les codes de la K2 sont publics. L'appli les charge donc au premier lancement :

| Touche | Code | Touche | Code |
|---|---|---|---|
| Power | `NEC:20:41` | MODE | `NEC:20:50` |
| Volume + | `NEC:20:42` | Volume − | `NEC:20:43` |
| Piste précédente | `NEC:20:1A` | Piste suivante | `NEC:20:4F` |
| Lecture / Pause | `NEC:20:1B` | EQ | `NEC:20:5A` |
| Basses + | `NEC:20:14` | Basses − | `NEC:20:56` |
| Aigus + | `NEC:20:4D` | Aigus − | `NEC:20:51` |

C'est une forte probabilité, pas une certitude : ces codes n'ont pas été essayés sur une vraie ASB-060. S'ils ne marchent pas, il reste la recherche automatique.

## Recherche automatique

À lancer depuis **Recherche**, ou depuis « Ça ne marche pas ».

1. Connectez le téléphone à la barre en **Bluetooth** et lancez une musique depuis le téléphone.
2. Posez le téléphone, le haut pointé vers la barre, et appuyez sur **Lancer la recherche**.

L'appli essaie les codes du plus probable au moins probable :

1. **Télécommandes de barres de son proches** : la touche Lecture/Pause (à défaut Power) d'une centaine de télécommandes connues, classées par ressemblance avec celle de l'ASB-060. Environ une minute.
2. **Même télécommande, réglée autrement** : la touche Lecture/Pause de la télécommande identique, essayée sur toutes les adresses. Environ quatre minutes.
3. **Tous les codes**, adresse par adresse, les adresses les plus courantes sur les appareils audio d'abord. Très long : seules les premières adresses sont vraiment utiles.

Elle **s'arrête toute seule** quand la barre réagit :

- la musique se met en pause : l'appli la relance, renvoie le code pour vérifier que c'est bien lui, puis propose de charger d'un coup toutes les touches de la télécommande qui correspond ;
- le volume du téléphone change (si la barre le transmet par Bluetooth) : même vérification ;
- le Bluetooth se coupe (barre éteinte ou source changée) : l'appli liste les derniers codes, à tester un par un.

Sans Bluetooth, la recherche fonctionne aussi, mais il faut appuyer soi-même sur **La barre a réagi**.

Les **réglages avancés** permettent de changer le temps entre deux essais, de sauter à une étape, ou de ne chercher que sur une adresse.

## Saisir un code à la main

Bouton **Éditer**, puis touchez une touche.

| Format | Exemple |
|---|---|
| NEC (adresse 8 bits) | `NEC:20:41` |
| NEC étendu (adresse 16 bits) | `NEC:B683:4D` |
| NEC étendu, commande 16 bits | `NEC:0B00:3DBB` |
| Pronto Hex | `0000 006D 0022 0002 0157 00AC …` |
| Durées brutes (µs) | `RAW:38000:9000,4500,560,560,…` |

Le menu **⋮** permet de choisir une autre télécommande proche, et d'exporter ou d'importer tous les codes (JSON, par le presse-papiers).

## Développement

```
./gradlew assembleRelease      # APK dans app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # tests
```

Le build a besoin du SDK Android 34 (`local.properties` → `sdk.dir=…`). L'APK est signé avec `app/debug.keystore`, une clé de test rangée dans le dépôt pour que toutes les versions aient la même signature.

Les tests font tourner la recherche et les écrans face à une fausse barre de son (Robolectric), sans téléphone. Ils ne remplacent pas un essai sur la vraie barre.

`app/src/main/assets/profiles.txt` contient les télécommandes proches et les adresses courantes. Il est généré à partir de [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) (licence CC0 1.0) :

```
python3 tools/build_profiles.py /chemin/vers/Flipper-IRDB
```
