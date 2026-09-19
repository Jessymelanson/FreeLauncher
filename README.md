# FreeLauncher

An Android home screen that restores Nova Launcher backups.

No accounts, no activation key, no paid tiernon telemetry, nothing to unlock. Install it,
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
buiot with a purpose, a raw application that doesn't communicate with any servers.
'it just works'

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
