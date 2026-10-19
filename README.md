<p align="center">
  <a href="https://youtu.be/ZJruuzCeL4I"><img src="docs/trailer.png" alt="hexkeys: watch the trailer" width="720"></a>
</p>

<p align="center">
  A keyboard for Android with big hexagonal keys, gestures instead of extra keys, and everything a keyboard is expected to do.
  <br>
  English · <a href="README.de.md">Deutsch</a>
</p>

<p align="center">
  <a href="https://github.com/nanbiz/hexkeys/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/nanbiz/hexkeys?sort=semver&color=5F72D9"></a>
  <a href="https://github.com/nanbiz/hexkeys/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/nanbiz/hexkeys/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-3FA393"></a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-A465B8">
</p>

## Why hexagons

Hexagons tile a surface with no gaps, so every key can be as large as the screen allows. hexkeys uses that room: seven and six keys per row, letters kept in their familiar QWERTZ order, and no keys that only exist to switch modes.

<p align="center">
  <img src="docs/keyboard.png" alt="The keyboard in the Dusk theme" width="300">
  &nbsp;&nbsp;
  <img src="docs/accents.png" alt="Holding e opens its accented letters" width="300">
</p>

## Gestures

| Gesture | What it does |
|---|---|
| Flick up on a letter | Types its capital |
| Hold a letter | Opens the accented letters your languages use, as a small honeycomb |
| Swipe across the letters | Types the word the path spells |
| Flick up on `.` or `?` | Types `,` or `!`; hold them for more marks |
| Tap enter | A new line in a field of several lines, otherwise the field's action |
| Flick up on enter, or hold it | Always runs the field's action, such as send or search |
| Tap `@#€` | Opens the symbols; `=\<` turns to the second page and `ABC` goes back |
| Flick up on `@#€` | Opens emoji |
| Flick sideways on space | Switches the language |
| Hold space and move | Steers the cursor through the text, up and down too |
| Slide in from the edge of the number row | Deletes as you slide, more the farther and the slower; slide back to put it back |
| Slide to the set distance (80% of the width) | Deletes everything before the cursor; slide back out to bring it back |
| Slide along the middle of the number row | Moves the cursor, one character per step |
| Hold shift or backspace (the optional keys) | Locks capitals, or deletes again and again |

## Features

- **Suggestions** for every typed word, with the next word predicted after a space. Autocorrect fixes every mistake by default, can be limited to obvious ones or switched off; learning new words is off by default and one switch away.
- **47 languages** from 53 downloadable dictionaries. The keyboard guesses the language from what you write and shows the languages on the space bar. Vietnamese types with Telex.
- **Dictation** that runs on the phone with NVIDIA's Parakeet model in 25 European languages, writing each stretch of speech while you keep talking.
- **Passwords** from your autofill service as chips in the suggestion strip, with the service's own button pinned in a fixed place.
- **Clipboard**: what you just copied waits in the strip for a one-tap paste; clips stay an hour, or for good once pinned.
- **Emoji** with search by name in every language you use.
- **Themes**: Dusk, Paper, Ember, seven more dark ones (Midnight, Aurora, Mocha, Forest, Abyss, Plum, Nordic), your device's colours (Android 12 and later), plain dark and plain light. The starter themes colour each letter by its QWERTZ row, so rearranged letters stay easy to find.
- **Two layouts**: space in the middle (the default) or space at the bottom. In the middle layout the key left of space is the comma, or one of backspace, emoji, clipboard, dictation, hide keyboard or a second space.
- **Sizes**: the whole keyboard, the letter keys and the number row scale on their own, and the number row can be left out.
- **English and German**: the app and its help speak English, and German when switched.

## Settings

Everything is under the keyboard's settings, which open from the app icon or the gear in the suggestion strip. **Help** there explains the keyboard in short, in the app's language.

| Setting | Choices (default) |
|---|---|
| App language | English (default), Deutsch |
| Autocorrect | Off, Obvious, All (default) |
| Learn new words | Off (default), on; Forget deletes what was learned |
| Delete by sliding in from | Left edge, right edge (default) |
| Slide this far to delete everything | 50% to 100% of the width (80% by default); a shorter distance also deletes more characters on the way, 100% is Off and keeps the slide as it was, where resting at the far side deletes whole words |
| Voice | Off (default), or On, which downloads the speech model once (670 MB) |
| Layout | Space in the middle (default), space at the bottom |
| Key left of space | Comma (default), backspace, emoji, clipboard, dictation, hide keyboard, second space; only in the middle layout |
| Shift and backspace keys | Off (default), on; always there without a number row |
| Theme | Any of the themes above (Dusk by default) |
| Whole keyboard | 70% to 130% of the height (100%) |
| Letter keys | 70% to 130% (100%) |
| Number row | On (default), off; without it the digits are on the symbols and deleting by sliding and the cursor slide along the row are gone, so the shift and backspace keys are there instead; the emoji search needs it too |
| Number row height | 50% to 150% (100%) |
| Languages | The dictionaries to suggest in; ticking one downloads it |
| Credits | Who made what the keyboard is built on |

In landscape the suggestion strip and the number row are left out to give the app room, and shift and backspace are keys.

## Privacy

hexkeys has no account, no ads and no trackers. It uses the network only to download the dictionaries, word statistics and speech model you ask for; everything you type and say stays on the phone.

## Install

Download the APK from the [latest release](https://github.com/nanbiz/hexkeys/releases/latest), open it on your phone, then turn hexkeys on under **Settings › System › Keyboard**. Android 9 or newer.

## Build

The core is written in Rust and the Android shell in Kotlin. You need Rust with the `aarch64-linux-android` and `x86_64-linux-android` targets, [cargo-ndk](https://github.com/bbqsrc/cargo-ndk), the Android SDK with platform 36 and NDK 27.0.12077973, and Gradle 9.3.1.

```sh
cargo test --workspace          # the core's tests
gradle -p android assembleDebug # builds the core for Android, then the APK
```

The APK lands in `android/app/build/outputs/apk/debug/`.

## Credits

hexkeys builds on the work of others; the app lists all of them under **Settings › Credits**.

- English dictionary: Android Open Source Project (Apache-2.0); other languages from [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries), each under its own license
- Word statistics for autocorrect: the [Leipzig Corpora Collection](https://wortschatz.uni-leipzig.de/en/download) (CC BY), D. Goldhahn, T. Eckart, U. Quasthoff, *Building Large Monolingual Dictionaries at the Leipzig Corpora Collection: From 100 to 200 Languages*, LREC 2012
- Speech recognition: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), ONNX Runtime (MIT), and NVIDIA's Parakeet TDT 0.6B v3 (CC BY 4.0)
- Emoji names: Unicode CLDR (Unicode-3.0)
- Icons: Material Symbols (Apache-2.0)
- Transliteration: [anyascii](https://github.com/anyascii/anyascii) (ISC)

## License

hexkeys is free software under the [GNU General Public License v3.0](LICENSE).
