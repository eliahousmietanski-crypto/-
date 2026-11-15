# Télécommande Aiwa ASB-060 pour Xiaomi Poco X5 5G

Appli Android qui remplace la télécommande d'origine de la barre de son **Aiwa ASB-060**. Elle passe par l'**émetteur infrarouge** intégré du Poco X5 5G, sur le bord supérieur du téléphone.

## Installation

1. Récupérez `app-release.apk` : dans l'onglet **Actions** du dépôt, ouvrez le dernier build et téléchargez l'artefact *aiwa-asb060-remote*.
2. Sur le téléphone, ouvrez l'APK. HyperOS/MIUI demande d'autoriser « Installer des applis inconnues » : acceptez.
3. Visez la barre de son avec le **haut** du téléphone.

## Copier la télécommande d'origine

Le Poco X5 peut **émettre** de l'infrarouge, mais il n'a pas de **récepteur** : il ne peut donc pas « lire » la télécommande d'origine. Je n'ai pas trouvé de liste publique des codes de l'ASB-060. L'appli contient donc un **scanner** qui retrouve chaque code en quelques minutes :

1. Connectez le Poco à la barre en **Bluetooth**, lancez une musique depuis le téléphone, puis ouvrez **Scanner**.
   Dès qu'un code met la musique en pause (Lecture/Pause, Power, Source…), le scan **s'arrête tout seul** et propose d'attribuer ce code à une touche.
2. Choisissez une adresse (commencez par `00`, puis les autres adresses proposées) et lancez **Auto ▶**. L'appli envoie les commandes `00` à `FF` une par une.
3. Quand la barre réagit (volume, changement de source, extinction…), appuyez sur **⏸ Pause**. Revenez avec **◀** et appuyez sur **Envoyer** pour retrouver le code exact.
4. Appuyez sur **✔ Ça a réagi** et choisissez la touche correspondante.
5. Les autres touches utilisent presque toujours la **même adresse** : relancez le balayage des commandes sur cette adresse et attribuez chaque touche.

Si aucune adresse proposée ne fonctionne, passez en **Balaye : ADRESSE** avec la commande fixée sur une valeur courante. Si vous trouvez des codes en ligne (Pronto Hex ou NEC), saisissez-les avec **Éditer**.

## Formats de code (mode Éditer)

| Format | Exemple |
|---|---|
| NEC (adresse 8 bits) | `NEC:00:46` |
| NEC étendu (adresse 16 bits) | `NEC:7F80:12` |
| Pronto Hex | `0000 006D 0022 0002 0157 00AC …` |
| Durées brutes (µs) | `RAW:38000:9000,4500,560,560,…` |

Le menu **⋮** permet d'exporter ou d'importer tous les codes au format JSON, via le presse-papiers.

## Compilation

```
./gradlew assembleRelease
```
Le build a besoin du SDK Android 34 (`local.properties` → `sdk.dir=…`). L'APK est signé avec la clé debug pour s'installer directement.
