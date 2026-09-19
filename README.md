# FreeLauncher

An Android home screen that restores Nova Launcher backups.

No accounts, no activation key, no paid tier, no telemetry, nothing to unlock. Install it,
make it your home screen, and it works.

This repository holds the signed APK. The source is not published here.

## Restoring a Nova backup

Nova writes `.novabackup` files. FreeLauncher reads them directly - **Nova does
not need to be installed**, and nothing is sent anywhere.

1. Put the `.novabackup` file on the phone.

2. Open FreeLauncher's settings and pick the file.

What comes back: your home screen pages and their layout, the dock, folders and
what is in them, widgets where the same widget is still installed, and icon
pack choices where that pack is still present.

## Why a launcher is different from other apps

A launcher is the first thing you see every time you unlock the phone, and it
runs for as long as the phone is on. That makes it the wrong place for account
prompts, upsells and background reporting.

FreeLauncher cannot talk to a server, and you do not have to take that on trust:
it does not request the `INTERNET` permission, so Android itself blocks any
network call the app might attempt. The full set of permissions it asks for is
local to the device:

| Permission | Why |
|---|---|
| `SET_WALLPAPER` | change the wallpaper |
| `EXPAND_STATUS_BAR` | pull down the status bar with a gesture |
| `INSTALL_SHORTCUT` | let apps add shortcuts to the home screen |
| `REQUEST_DELETE_PACKAGES` | uninstall an app by dragging it |
| `VIBRATE` | haptic feedback |
| `ACCESS_HIDDEN_PROFILES` | show work and private profile apps |
| `MODIFY_QUIET_MODE` | pause and resume a work profile |

Check for yourself:

```bash
aapt dump permissions FreeLauncher-release.apk
```

Restoring a backup is no exception. The `.novabackup` file is read on the
device and never leaves it.

## Verify what you downloaded

| File | SHA-256 |
|---|---|
| `FreeLauncher-release.apk` | `23eb9f4b46f2266dfbd309b690a7ae906b4bb2781459b9ac6f65c3c84c39e383` |

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

That prints `v1 scheme (JAR signing): false`. It is not missing - with
`minSdk 26`, apksigner only verifies the schemes that platform range uses. Add
`--min-sdk-version 21` and v1, v2 and v3 all verify.

## Requirements

Android 8.0 or later (minSdk 26), built against SDK 36.

## If you want to say thanks

FreeLauncher is free and stays free. There is nothing to unlock, and nothing
here is gated behind a donation.

If you get use out of it and feel like sending something, these are the only
addresses I use. Check them character by character - transfers on both chains
are irreversible.

| Chain | Address |
|---|---|
| Solana | `862YZXoRvaoTiP1AkQEEZ5FFGgPFsUhbEoRu4r44RhSe` |
| Ethereum | `0xF890c6A128920D145D47753D0b1159fA4Db2861d` |

Send only native SOL or ETH, or standard tokens on those chains. Anything sent
on a different network is lost.

No obligation either way. A bug report is worth just as much.
