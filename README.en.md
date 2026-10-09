# 📦 ItzArchiv

[Bahasa Indonesia](README.md) | **English**

Repository: https://github.com/eLgorythm/ItzArchiv

ItzArchiv is an Android app for extracting and compressing archives. It has a minimal dark interface, uses Android's built-in file/folder picker (SAF), and automatically follows the system language: Indonesian or English.

## Supported formats

**Extract:** ZIP, 7Z, RAR, TAR, TAR.GZ (`.tgz`), TAR.BZ2 (`.tbz2`), TAR.XZ (`.txz`), GZ, BZ2, and XZ.

**Compress to:** ZIP, 7Z, TAR, TAR.GZ, TAR.BZ2, and TAR.XZ.

## Main features

- Extract archives to a folder chosen by the user.
- Compress multiple files using their file names.
- Compress an entire folder while preserving its folder structure.
- Compression passwords for ZIP AES-256 and 7Z; password-protected archives can also be extracted.
- TAR formats do not support passwords because TAR has no password standard.
- Optional inclusion of hidden files/folders when compressing a folder; disabled by default. `.trashed-*` files are always skipped.
- The About card appears once per version during normal app entry, can be reopened from the **About** button, and closes when the empty area outside the card is tapped.
- The app language automatically follows Android's system language: `values-in` for Indonesian and `values-en` for English. Other languages fall back to English.
- Open archives from a file manager with **Open with → ItzArchiv**.

## How to use

1. Install the APK.
2. To extract, choose **Select Archive & Extract**, enter the password if the archive is protected, then choose a destination folder.
3. To compress, choose files or a folder, set the name, format, hidden-file option (folder mode only), and password in the compression dialog, then choose the output location.
4. The save dialog for compression starts in the source's parent folder so the result does not land inside the folder being compressed.

## Background processing and cancellation

Work runs in a Foreground Service, not inside the Activity:

- If the app is minimized, the screen turns off, the Activity closes, or the app is swiped from Recents, the process keeps running.
- The notification shows the percentage and file count, plus a **Cancel** action.
- Reopen the app or tap the notification to see the latest status.
- Failed or cancelled compression deletes the incomplete output archive. Cancelled extraction keeps files that already finished extracting.
- Android limits still apply: **Force stop** from Settings and a phone shutdown/restart will stop the process.

The app does not request broad storage permission. The user grants access to each file or folder through SAF.

## Honest limitations

- RAR can only be extracted, not created, because RAR is a proprietary format.
- Split archives such as `.zip.001` and `.part1.rar` are not supported yet.
- TAR, TAR.GZ, TAR.BZ2, and TAR.XZ cannot be password-protected.
- The file picker and save dialogs belong to Android/DocumentsUI; their appearance follows the system.

## Changes in v1.9 — renamed to ItzArchiv

The app is now **ItzArchiv**. The Android package also uses the new identity `me.fndlabs.itzarchiv`, so Android treats it as a new app and it will not overwrite a previous installation. The repository and all documentation now use the ItzArchiv name.

## Changes in v1.8 — automatic ID/EN language

All user-facing text was moved into Android resources. The home screen, Extract dialog, Compress dialog, About card, Log popup, process status, major error messages, and Foreground Service notifications use the system language. There is no manual language switch.

## Build from source

This project can be built as a standard Gradle project in Android Studio, or with the included manual build script:

```bash
./build-manual.sh
# output: build-manual/ItzArchiv-debug.apk
```

The manual script requires JDK 17, Android SDK platform 34, and build-tools 35.0.0. Third-party libraries live in `libs/` and are downloaded by the script if missing.

## Libraries

- Apache Commons Compress
- Zip4j 2.11.5
- JunRAR
- XZ for Java
- Apache Commons IO, Commons Lang, Commons Codec, and SLF4J API
