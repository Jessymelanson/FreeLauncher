# FreeLauncher

An Android home screen that restores Nova Launcher backups.

No accounts, no activation key, no paid tier, no telemetry, nothing to unlock.
Install it, make it your home screen, and it works.

This repository holds the source and the signed APK. Build it yourself, or
install the release and check it against the hash below.

## Restoring a Nova backup

Nova writes `.novabackup` files. FreeLauncher reads them directly, so Nova does
not need to be installed, and the file is read on the device.

1. Put the `.novabackup` file on the phone.
2. Open FreeLauncher's settings and pick the file.

What comes back: your home screen pages and their layout, the dock, folders and
their contents, widgets where the same widget is still installed, and icon pack
choices where that pack is still present.

## Permissions

FreeLauncher does not request the `INTERNET` permission, so Android blocks any
network call it might attempt. Everything it does ask for is local to the
device:

| Permission | Why |
|---|---|
| `SET_WALLPAPER` | change the wallpaper |
| `EXPAND_STATUS_BAR` | pull down the status bar with a gesture |
| `INSTALL_SHORTCUT` | let apps add shortcuts to the home screen |
| `REQUEST_DELETE_PACKAGES` | uninstall an app by dragging it |
| `VIBRATE` | haptic feedback |
| `ACCESS_HIDDEN_PROFILES` | show work and private profile apps |
| `MODIFY_QUIET_MODE` | pause and resume a work profile |

```bash
aapt dump permissions FreeLauncher-release.apk
```

## Install it

Android will not install an app from outside a store until you allow it once.

1. On the phone, open [Releases](../../releases) and download
   `FreeLauncher-release.apk`.

2. Tap the downloaded file. Android will say it is not allowed to install
   unknown apps from this source.

3. Tap **Settings** in that message, turn on **Allow from this source**, then
   press back and tap **Install**.

4. Make it your home screen. Press the Home button and Android will ask which
   launcher to use, so pick FreeLauncher and choose Always. If it does not ask,
   it is under Settings, then Apps, then Default apps, then Home app.

You can turn that permission back off afterwards. It applies to the app you
downloaded with, usually your browser, and not to the phone as a whole. Updating
later is the same steps, and installing over the top keeps your data.

## Verify what you downloaded

| File | Version | SHA-256 |
|---|---|---|
| `FreeLauncher-release.apk` | 1.0.3 | `d7d76fe6afbabcfdf52b33aa9f8eb886aca0f41edc5499f5b53d86dcdbdc7f74` |

```bash
sha256sum FreeLauncher-release.apk                    # Linux, macOS, git bash
certutil -hashfile FreeLauncher-release.apk SHA256    # Windows
```

## Signing

```
CN=JApps, OU=JFamily, O=JApps, C=CA
753bf85dbbf2cd55862e494733ffe69a14eff96d9c0b8e0883fe347464c7a02f
```

```bash
apksigner verify --print-certs FreeLauncher-release.apk
```

This reports `v1 scheme (JAR signing): false`, which is expected at `minSdk 26`.
Pass `--min-sdk-version 21` and v1, v2 and v3 all verify.

## Requirements

Android 8.0 or later (minSdk 26), built against SDK 36.

## If you want to say thanks

FreeLauncher is free and stays free. There is nothing to unlock and nothing
gated behind a donation.

If you get use out of it and feel like sending something, these are the only
addresses I use. Check them character by character, since transfers on both
chains are irreversible.

| Chain | Address |
|---|---|
| Solana | `862YZXoRvaoTiP1AkQEEZ5FFGgPFsUhbEoRu4r44RhSe` |
| Ethereum | `0xF890c6A128920D145D47753D0b1159fA4Db2861d` |

Send only native SOL or ETH, or standard tokens on those chains. Anything sent
on a different network is lost.

A bug report is just as welcome.
