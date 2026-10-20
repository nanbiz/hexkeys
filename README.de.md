<p align="center">
  <a href="https://youtu.be/ZJruuzCeL4I"><img src="docs/trailer.png" alt="hexkeys: Trailer ansehen" width="720"></a>
</p>

<p align="center">
  Eine Tastatur für Android mit großen sechseckigen Tasten, Gesten statt zusätzlicher Tasten und allem, was man von einer Tastatur erwartet.
  <br>
  <a href="README.md">English</a> · Deutsch
</p>

<p align="center">
  <a href="https://github.com/nanbiz/hexkeys/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/nanbiz/hexkeys?sort=semver&color=5F72D9"></a>
  <a href="https://github.com/nanbiz/hexkeys/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/nanbiz/hexkeys/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-3FA393"></a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-A465B8">
</p>

## Warum Sechsecke

Sechsecke füllen eine Fläche lückenlos, daher kann jede Taste so groß sein, wie der Bildschirm es zulässt. hexkeys nutzt diesen Platz: sieben und sechs Tasten pro Reihe, Buchstaben in der gewohnten QWERTZ-Anordnung und keine Tasten, die nur zum Umschalten von Modi da sind.

<p align="center">
  <img src="docs/keyboard.png" alt="Die Tastatur im Design Dämmerung" width="300">
  &nbsp;&nbsp;
  <img src="docs/accents.png" alt="Halten von e öffnet die Buchstaben mit Akzent" width="300">
</p>

## Gesten

| Geste | Wirkung |
|---|---|
| Auf einem Buchstaben nach oben wischen | Tippt den Großbuchstaben |
| Einen Buchstaben halten | Öffnet die Buchstaben mit Akzent, die deine Sprachen brauchen, als kleine Honigwabe |
| Über die Buchstaben wischen | Tippt das Wort, das der Weg buchstabiert |
| Auf `.` oder `?` nach oben wischen | Tippt `,` oder `!`; halten für weitere Zeichen |
| Enter antippen | Eine neue Zeile in einem mehrzeiligen Feld, sonst die Aktion des Felds |
| Auf Enter nach oben wischen oder es halten | Führt immer die Aktion des Felds aus, etwa Senden oder Suchen |
| `@#€` antippen | Öffnet die Symbole; `=\<` wechselt zur zweiten Seite und `ABC` geht zurück |
| Auf `@#€` nach oben wischen | Öffnet die Emoji |
| Auf der Leertaste zur Seite wischen | Wechselt die Sprache |
| Die Leertaste halten und bewegen | Lenkt den Cursor durch den Text, auch nach oben und unten |
| Vom Rand der Zahlenreihe hineinziehen | Löscht die letzten Buchstaben einzeln, dann ganze Wörter bis zu den letzten zehn, dann auf der anderen Seite Wörter immer schneller, solange du dort bleibst; zurückziehen, um es wiederherzustellen |
| In der Mitte der Zahlenreihe ziehen | Bewegt den Cursor, ein Zeichen pro Schritt |
| Umschalt- oder Rücktaste halten (die optionalen Tasten) | Sperrt Großbuchstaben oder löscht wiederholt |

## Funktionen

- **Vorschläge** für jedes getippte Wort, mit dem nächsten Wort nach einem Leerzeichen. Die Autokorrektur verbessert standardmäßig jeden Fehler, lässt sich auf eindeutige Fälle beschränken oder ausschalten; das Lernen neuer Wörter ist standardmäßig aus und mit einem Schalter aktiviert.
- **47 Sprachen** aus 53 herunterladbaren Wörterbüchern. Die Tastatur errät die Sprache aus dem, was du schreibst, und zeigt die Sprachen auf der Leertaste. Vietnamesisch wird mit Telex getippt.
- **Diktat**, das auf dem Telefon mit NVIDIAs Parakeet-Modell in 25 europäischen Sprachen läuft und jeden Sprechabschnitt schreibt, während du weitersprichst.
- **Passwörter** deines Autofill-Dienstes als Chips in der Vorschlagsleiste, mit der eigenen Schaltfläche des Dienstes an einem festen Platz.
- **Zwischenablage**: Das gerade Kopierte wartet in der Leiste auf ein Einfügen mit einem Tipp; Einträge bleiben eine Stunde, angeheftet dauerhaft.
- **Emoji** mit Suche nach Namen in jeder Sprache, die du verwendest.
- **Designs**: Dämmerung, Papier, Glut, sieben weitere dunkle (Mitternacht, Aurora, Mokka, Wald, Abgrund, Pflaume, Nordisch), die Farben deines Geräts (ab Android 12), schlicht dunkel und schlicht hell. Die Start-Designs färben jeden Buchstaben nach seiner QWERTZ-Reihe, damit verschobene Buchstaben leicht zu finden bleiben.
- **Zwei Layouts**: Leertaste in der Mitte (Standard) oder Leertaste unten. Im Layout mit der Leertaste in der Mitte ist die Taste links davon das Komma, die Rücktaste oder eine zweite Leertaste.
- **Größen**: Die ganze Tastatur, die Buchstabentasten und die Zahlenreihe lassen sich einzeln skalieren, und die Zahlenreihe kann entfallen.
- **Englisch und Deutsch**: Die App und ihre Hilfe sprechen Englisch, und Deutsch, wenn umgestellt.

## Einstellungen

Alles findest du in den Einstellungen der Tastatur, die sich über das App-Symbol oder das Zahnrad in der Vorschlagsleiste öffnen; Layout, Design und Größen haben dort eine eigene Seite **Aussehen**, über der Tastatur, wie sie daraus wird. Die **Hilfe** im Menü der Einstellungen erklärt die Tastatur kurz, in der Sprache der App.

| Einstellung | Auswahl (Standard) |
|---|---|
| App-Sprache | English (Standard), Deutsch |
| Autokorrektur | Aus, Eindeutige, Alle (Standard) |
| Neue Wörter lernen | Aus (Standard), an; Vergessen löscht das Gelernte |
| Löschen durch Ziehen von | Linker Rand, rechter Rand (Standard) |
| Bereiche | Wo die Bereiche für Buchstaben, Wörter und das endlose Löschen beginnen, gezogen auf einem Bild der Zahlenreihe (Wörter ab 45 %, endlos ab 90 % der Breite); die letzte Grenze ans Ende gezogen lässt den endlosen Bereich weg |
| Buchstabe für Buchstabe durch | Die letzten 3 bis 30 Buchstaben (10) |
| Wort für Wort bis | Die letzten 2 bis 30 Wörter (10) |
| Spracheingabe | Aus (Standard), oder An, was das Sprachmodell einmalig herunterlädt (670 MB) |
| Layout | Leertaste in der Mitte (Standard), Leertaste unten |
| Taste links der Leertaste | Komma (Standard), Rücktaste, zweite Leertaste; nur im Layout mit der Leertaste in der Mitte |
| Umschalt- und Rücktaste | Aus (Standard), an; ohne Zahlenreihe immer vorhanden |
| Design | Eines der oben genannten Designs (standardmäßig Dämmerung) |
| Ganze Tastatur | 70 % bis 130 % der Höhe (100 %) |
| Buchstabentasten | 70 % bis 130 % (100 %) |
| Zahlenreihe | An (Standard), aus; ohne sie liegen die Ziffern bei den Symbolen, und Löschen durch Ziehen und Cursor-Bewegen entlang der Reihe entfallen, daher sind stattdessen die Umschalt- und Rücktaste vorhanden; die Emoji-Suche braucht sie ebenfalls |
| Höhe der Zahlenreihe | 50 % bis 150 % (100 %) |
| Sprachen | Die Wörterbücher, in denen vorgeschlagen wird; Ankreuzen lädt eines herunter |

Im Querformat entfallen die Vorschlagsleiste und die Zahlenreihe, damit die App Platz hat, und Umschalt- und Rücktaste sind Tasten.

## Datenschutz

hexkeys hat kein Konto, keine Werbung und keine Tracker. Das Netzwerk wird nur genutzt, um die Wörterbücher, Wortstatistiken und das Sprachmodell herunterzuladen, die du anforderst; alles, was du tippst und sagst, bleibt auf dem Telefon.

## Installation

Lade die APK vom [neuesten Release](https://github.com/nanbiz/hexkeys/releases/latest) herunter, öffne sie auf deinem Telefon und aktiviere dann hexkeys unter **Einstellungen › System › Tastatur**. Android 9 oder neuer.

## Build

Der Kern ist in Rust geschrieben und die Android-Hülle in Kotlin. Du brauchst Rust mit den Targets `aarch64-linux-android` und `x86_64-linux-android`, [cargo-ndk](https://github.com/bbqsrc/cargo-ndk), das Android SDK mit Plattform 36 und NDK 27.0.12077973 sowie Gradle 9.3.1.

```sh
cargo test --workspace          # the core's tests
gradle -p android assembleDebug # builds the core for Android, then the APK
```

Die APK landet in `android/app/build/outputs/apk/debug/`.

## Danksagungen

hexkeys baut auf der Arbeit anderer auf; die App listet alle unter **Danksagungen** im Menü der Einstellungen.

- English dictionary: Android Open Source Project (Apache-2.0); other languages from [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries), each under its own license
- Word statistics for autocorrect: the [Leipzig Corpora Collection](https://wortschatz.uni-leipzig.de/en/download) (CC BY), D. Goldhahn, T. Eckart, U. Quasthoff, *Building Large Monolingual Dictionaries at the Leipzig Corpora Collection: From 100 to 200 Languages*, LREC 2012
- Speech recognition: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), ONNX Runtime (MIT), and NVIDIA's Parakeet TDT 0.6B v3 (CC BY 4.0)
- Emoji names: Unicode CLDR (Unicode-3.0)
- Icons: Material Symbols (Apache-2.0)
- Transliteration: [anyascii](https://github.com/anyascii/anyascii) (ISC)

## Lizenz

hexkeys ist freie Software unter der [GNU General Public License v3.0](LICENSE).
