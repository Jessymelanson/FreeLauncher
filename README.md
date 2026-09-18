# FreeLauncher

An Android home screen that restores Nova Launcher backups.

No accounts, no activation key, no paid tier, nothing to unlock. Install it,
make it your home screen, and it works.

- **Android 8.0+** (minSdk 26), built against SDK 36
- `FreeLauncher-release.apk` is the one to install

---

## Restoring a Nova backup

Nova writes `.novabackup` files. FreeLauncher reads them directly; Nova does not
need to be installed, and nothing is sent anywhere.

1. Put the `.novabackup` file on the phone. Over USB that is:

```bash
adb push "2026-09-14_19-36.novabackup" /sdcard/Download/
```

   Anything that reaches the phone's storage works just as well: a cable and
   drag-and-drop, a USB stick, a network share, or a cloud folder the system
   file picker can see.

2. Open **FreeLauncher Settings** (it appears in the app drawer), then
   **Restore a Nova backup**, and pick the file.

### What comes across

| | |
|---|---|
| Home screen pages, with every icon in its original cell | yes |
| The dock, including gaps | yes |
| Folders and their contents | yes |
| Icons exactly as Nova drew them, icon pack and all | yes |
| Grid size, dock columns, icon shape, drawer columns | yes |
| Widgets | **no, see below** |

Icons are worth a word. Nova stores the finished bitmap of every icon, so an
icon pack's artwork comes back without the pack being installed. Those bitmaps
are copied into FreeLauncher and used in place of whatever the app itself ships.

Apps that are not installed on this phone are skipped, and the import names
every one of them so nothing disappears quietly.

**Widgets cannot be restored, by anyone.** A widget is a binding between one
launcher's `AppWidgetHost` and one widget instance, issued by the system on the
phone that created it. That binding is not in the backup file and cannot be
recreated from it. FreeLauncher keeps each widget's place on the page as a
labelled gap; tap it to put a widget back.

---

## Backing up FreeLauncher itself

**Settings → Back up this layout** writes a `.flbackup` file containing the
layout, every setting and all custom icons. **Restore a FreeLauncher backup**
reads it back.

It is an ordinary ZIP with JSON inside, deliberately: a backup you cannot open
on a computer to see what went wrong is a backup you cannot trust.

---

## What it does

**Home screen** - pages of icons on a grid you choose, from 3x3 up to 10x10.
Drag to rearrange, drop one icon on another to make a folder, drag to the top of
the screen to remove. Carry an icon to the right edge of the last page and a new
page is created under it.

**Pages** - tap the page dots, or hold empty space and choose *Edit pages*. Add,
delete, drag to reorder, and pick which page Home returns to.

**App drawer** - swipe up from anywhere on the home screen. Grid or list, with
search that ranks sensibly: typing `ph` puts Phone and Photos above JPhone and
JPhotos, and `gm` finds Google Maps. Enter opens the top result.

**Hold an app in the drawer and drag it out** to place it exactly where you want
it. The drawer gets out of the way as the icon comes with you, so you can see
where it is going; drop it on another icon to make a folder, or let go anywhere
meaningless and nothing is placed at all.

**A-Z down the right-hand edge**, once there are enough letters to be worth it.
Drag it rather than tap it: twenty-six letters on a phone is about twenty pixels
each, which is half a finger, so the letter under the thumb is shown in a bubble
beside it and the finger can correct itself on the way. It is hidden while a
search is typed, because an alphabet is not what is on screen then.

**Widgets** - hold empty space, choose *Widgets*. Sizes are shown in grid cells
rather than dp. Hold a placed widget and choose *Resize* for drag handles.

**Long-press an icon** - the app's own shortcuts first, then Change icon, App
info, Remove, and Uninstall for anything that is not a system app. In the
drawer: the same shortcuts, then Add to home screen, App info, Hide from drawer,
Uninstall. Inside an open folder a hold lifts the app: move the finger to
rearrange the folder or to drag the app out onto the desktop, or let go without
moving for the same menu, where Remove reads *Remove from folder*.

**App shortcuts** - the things an app publishes for itself: *New tab*, *New
incognito tab*, *New message*, a contact you speak to often. They sit at the top
of the long-press menu with their own icons. Tap one to open it; **hold one to
put it on the home screen** as an icon of its own, which is the only way to get
there - a launcher cannot pin something the publishing app has not offered.

Up to four are shown, ranked by the app that published them, which is both what
the platform's own menus do and about where the menu stops fitting on a short
screen.

**Rename** - an app's label is the app's, and it is often not what you would
call it: three things all called Messages, a bank whose app is named after a
product nobody uses, an app in a language you do not read. Any icon on the home
screen can be given a name of its own, and clearing the field puts the app's own
name back.

**Change icon** - pick any picture for any icon on the home screen. It is
cropped square from the middle and then masked to whatever icon shape is set, so
a chosen icon is the same shape as everything around it.

*Use the original icon* appears once one is set and puts back exactly what was
there before, which is not always the app's own icon: a pinned web shortcut goes
back to its site's favicon and a pinned app shortcut to its own picture. The
chosen icon is stored beside the original rather than over it, so nothing is
lost by trying one.

Uninstall always asks first. It sits one row below Remove on a surface people
spend their time dragging things around on, and it is the only thing in that
menu that putting the icon back will not undo.

**An icon whose app has gone** - tapping it offers to fetch the app from the
Play Store, or to take the icon off the home screen. Icons outlive their apps
on purpose: uninstalling something should not silently rearrange the home
screen, and a restored backup names apps this phone may never have had.

**Private space** - Android 15's second, lockable profile. FreeLauncher keeps
its apps out of the drawer and out of search entirely, and shows them in one
place: **hold the grab handle at the top of the app drawer**.

There is no row at the end of the drawer, no header and no gap, because the
point of a private space is that its existence is not advertised. On a phone
with no private profile the gesture is not wired up at all - holding the handle
behaves exactly like tapping it, so it cannot even be used to find out whether
one is set up.

The lock is Android's, not this app's. Unlocking asks the platform to lift quiet
mode and the platform raises its own credential prompt; the PIN never reaches
this process. Locking again is the padlock in the panel's header. Apps are added
to a private space from Android's own settings, which the gear icon opens.

Inside the panel the apps rearrange the same way a folder's do: hold one to lift
it and move it where you want, or let go without moving for its menu. The order
is remembered, and an app installed into private space later joins the end
rather than pushing your arrangement around.

That menu also offers **Add to home screen**. The icon it places carries
Android's private-profile badge and opens the private copy of the app, not the
personal one - and it is a visible icon on your home screen, which is worth
knowing before you place one. Tapping it while private space is locked opens the
panel to unlock rather than doing nothing: an app in a locked space cannot start
at all, and Android does not report that as a failure, so there is nothing to
notice unless the launcher checks first.

**Icon packs** - any pack from Play that works with Nova or ADW works here.
There has never been an Android API for this; what exists is a convention ADW
started in 2010 and everyone copied, and thousands of packs follow it exactly.

Apps the pack has an icon for get it, drawn as the pack drew it and *not* masked
to the launcher's icon shape -- a pack icon is a finished piece of art with its
own silhouette, and clipping it to a circle would cut the artwork rather than a
background. Apps the pack says nothing about keep their own icon dressed in the
pack's style, where the pack provides one: its background plate, its mask, its
overlay, at its own scale. A pack that offers no such treatment leaves them to
the launcher's own shaping, which is better than a home screen where a fifth of
the icons match and the rest do not.

**Notification dots** - a dot on apps that have something waiting, including on
a closed folder when something inside it does. Off until you turn it on, because
turning it on asks for Android's notification access, and that same grant lets
an app read the text of every notification on the phone. FreeLauncher reads the
name of the package a notification came from and nothing else; when the listener
is not connected there are no dots at all, rather than stale ones.

Ongoing notifications are ignored on purpose. A media player, a download and
"this app is running in the background" are permanent while they last, so a dot
for them would be a dot that never goes out and never means anything.

**Customisation** - theme (system / light / dark / AMOLED black), eight accent
colours, icon shape, icon pack, icon and label size, wallpaper dimming, grid and
dock columns, drawer style and opacity, hidden apps, status bar, swipe-down
action.

**Shortcuts from other apps** - a browser's *Add to Home screen*, and anything
else that pins a shortcut, arrives through `requestPinShortcut` and is confirmed
before it is placed. If one later refuses to open, tapping it says why: the app
no longer has it, the app has disabled it (with the app's own reason), or the
app still lists it and simply refused. Each of those offers to take the icon
off. It used to do nothing at all, which is indistinguishable from the tap not
registering.

**Screen readers** - every icon, in the drawer, on the home screen, in the dock
and inside folders, is a labelled button with an open action and a menu action.

This needed saying out loud because of how the launcher is built: every gesture
here is hand-written pointer input, for reasons the gesture code explains at
length, and pointer input carries no accessibility information at all. A screen
reader found a label and nothing else -- no role, no actions, and with icon
labels switched off, nothing whatsoever. A home screen that cannot be operated
without sight is a phone that cannot be operated without sight.

The one deliberate omission is the drawer's grab handle, which announces only
that it closes the drawer. Announcing its hold would announce private space, out
loud, on a phone that may not have one.

### One honest caveat

Swipe down for notifications uses a private system call that Android blocks on
some versions. Where it is blocked the gesture does nothing rather than doing
something else. The setting says so.

The other one is not this launcher's to fix. `INSTALL_SHORTCUT`, the pre-Android-8
way for an app to add a shortcut, is refused outright by Android 15 and later:
the system drops the broadcast before any launcher sees it, logging *"no longer
supported. It will not be delivered."* FreeLauncher still listens for it, both
in the manifest and at runtime, which is what makes it work on Android 8 to 14 -
but on a newer phone an app that still uses that API cannot add a shortcut to
any launcher, stock ones included.

---

## Building

```bash
./gradlew assembleRelease
```

Needs JDK 17 and an Android SDK with platform 36. The release build is signed
with the real key at `~/JApps-Signing/keystore.properties`, falling back to a
copy beside the project and then to the debug key, so a checkout with no key at
all still builds something installable.

Build a debug APK only for debugging. A `debuggable` APK holds ART back from
optimising, and on a launcher that shows up as stutter for the first few swipes
after every cold start.

---

## How it is put together

```
data/      layout, settings, the app list, the icon cache
nova/      reading .novabackup files and mapping them onto the model
ui/home/   workspace, dock, folders, drag and drop, widgets, page editor
ui/drawer/ the app drawer and its search
ui/settings/ everything configurable, plus import and backup
```

The layout is one JSON file written through a temp file and a rename. A launcher
is killed abruptly and often, and that is normal operation rather than a crash;
a half-written layout file is a phone with no home screen on it.

### The Nova backup format

Undocumented, so this is written against a real file rather than a spec. A
`.novabackup` is a ZIP:

| entry | what it is |
|---|---|
| `nova.db` | SQLite. `favorites` is AOSP Launcher3's schema with Nova's columns added on the end; also `drawer_groups` and `appgroups` |
| `nova.xml` | Nova's preferences, in SharedPreferences XML |
| `cards.datastore`, `supportDetails.txt`, `com.teslacoilsw.launcher_preferences.xml` | none of which describe the home screen |

In `favorites`: `container` is `-100` for the desktop and `-101` for the dock,
`-200 - groupId` for a drawer group, and otherwise the id of the folder holding
the row. `itemType` is 0 app, 1 shortcut, 2 folder, 4 widget, 6 deep shortcut.
`cellX`/`cellY` are REAL rather than INTEGER, because Nova's subgrid lets an icon
sit on a half cell. `icon` is a PNG.

Stored intents are `Intent.toUri(URI_INTENT_SCHEME)` strings, but they are read
with a regex rather than `Intent.parseUri`: Nova adds keys the platform parser
does not know - `extendedLaunchFlags` is on every app row - and on the versions
where `parseUri` throws on an unknown key, that would fail every row instead of
degrading. For an installed app the stored intent is not what should launch it
anyway; the component is resolved through `LauncherApps` at launch time, so the
icon keeps working after an update moves the app's entry activity.
