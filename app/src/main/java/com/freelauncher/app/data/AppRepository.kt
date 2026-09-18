package com.freelauncher.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator

/**
 * What is installed, and how to start it.
 *
 * Everything goes through LauncherApps rather than PackageManager. The two look
 * interchangeable for listing apps and are not: LauncherApps reports every
 * profile the device has, so a work profile's apps appear, and it hands back a
 * badged icon with the briefcase already drawn on it. Queried through
 * PackageManager the same apps are either missing or indistinguishable from
 * personal ones, and launching them starts the wrong user's copy.
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val launcherApps =
        appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager =
        appContext.getSystemService(Context.USER_SERVICE) as UserManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Private space, kept apart from everything else.
     *
     * Android 15's private profile arrives through the same `userProfiles` list
     * as a work profile, so the loop below would otherwise fold its apps into
     * the drawer beside the personal ones -- which is the one thing a private
     * space must never do. They are split off here, at the only place that
     * reads the profile list, so there is no second path that could forget.
     */
    private val _privateApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val privateApps: StateFlow<List<AppEntry>> = _privateApps.asStateFlow()

    /** The private profile, or null on a device that has none. */
    private val _privateProfile = MutableStateFlow<UserHandle?>(null)
    val privateProfile: StateFlow<UserHandle?> = _privateProfile.asStateFlow()

    /**
     * That profile's serial number, which is what a stored item records.
     *
     * Kept separately rather than derived from the handle on demand, because
     * going the other way round does not survive the space being locked: a
     * locked profile is a *stopped* profile, and getUserForSerialNumber can
     * hand back nothing for one. An item's recorded serial is then
     * unresolvable, which is exactly when the launcher most needs to recognise
     * it -- the user has just tapped a private app's icon and is waiting to be
     * asked to unlock. The serial is read once, while the profile is being
     * listed, and remembered.
     */
    private val _privateSerial = MutableStateFlow<Long?>(null)
    val privateSerial: StateFlow<Long?> = _privateSerial.asStateFlow()

    /**
     * Whether private space is locked right now.
     *
     * "Locked" is quiet mode -- the platform's own switch, the same one a work
     * profile is paused with. Defaults to locked, so a device with no private
     * profile at all never reads as unlocked.
     */
    private val _privateLocked = MutableStateFlow(true)
    val privateLocked: StateFlow<Boolean> = _privateLocked.asStateFlow()

    /**
     * Sorted the way the user's language sorts, not the way ASCII does.
     *
     * A plain sortedBy on the label puts every lowercase name after every
     * uppercase one and files accented letters at the end of the alphabet,
     * which is wrong in most of Europe and very obviously wrong to anyone
     * scrolling an A-Z list.
     */
    private val collator: Collator = Collator.getInstance().apply {
        strength = Collator.PRIMARY
    }

    /**
     * Told the name of every package that appears, changes or goes away.
     *
     * Exists so the icon cache can drop that package's bitmaps. Without it an
     * app that changes its icon in an update keeps the old one on the home
     * screen until the launcher is killed, which looks exactly like the update
     * having failed.
     */
    var onPackageInvalidated: ((String) -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate(packageName)

        override fun onPackagesAvailable(
            packageNames: Array<out String>, user: UserHandle, replacing: Boolean,
        ) = packageNames.forEach { invalidate(it) }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>, user: UserHandle, replacing: Boolean,
        ) = packageNames.forEach { invalidate(it) }

        private fun invalidate(packageName: String) {
            onPackageInvalidated?.invoke(packageName)
            refresh()
        }
    }

    fun start() {
        launcherApps.registerCallback(callback, mainHandler)

        // Literals rather than the Intent constants, which are API 34. They are
        // compile-time strings either way, and spelling them out keeps the
        // minSdk 26 build free of an availability guard for something an older
        // platform simply never broadcasts.
        val filter = IntentFilter().apply {
            addAction("android.intent.action.PROFILE_AVAILABLE")
            addAction("android.intent.action.PROFILE_UNAVAILABLE")
            addAction("android.intent.action.MANAGED_PROFILE_AVAILABLE")
            addAction("android.intent.action.MANAGED_PROFILE_UNAVAILABLE")

            // Added and removed, not only locked and unlocked. Setting up or
            // deleting a private space changes the profile list itself, and
            // nothing else tells a launcher that: no package appears or
            // disappears in the user this process runs as, so the package
            // callbacks stay silent and the launcher goes on believing in a
            // profile that has been deleted.
            addAction("android.intent.action.PROFILE_ADDED")
            addAction("android.intent.action.PROFILE_REMOVED")
            addAction("android.intent.action.MANAGED_PROFILE_ADDED")
            addAction("android.intent.action.MANAGED_PROFILE_REMOVED")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(profileReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(profileReceiver, filter)
            }
        }
        refresh()
    }

    fun stop() {
        runCatching { launcherApps.unregisterCallback(callback) }
        runCatching { appContext.unregisterReceiver(profileReceiver) }
    }

    private var refreshJob: Job? = null

    /**
     * Rebuild the app list.
     *
     * Coalesced, because package broadcasts arrive in bursts: a Play Store
     * batch update fires one callback per app, and each one used to start its
     * own scan of every profile -- a binder call returning a couple of hundred
     * activities, sorted, then published. Publishing also replaces the list
     * identity, so the drawer and the home screen recomposed once per app
     * updated. Cancelling the previous scan and waiting for the burst to settle
     * turns twenty of those into one.
     *
     * The very first load is not delayed. Nothing is on screen waiting for it
     * on a later refresh, but on the first one the app drawer is.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (_loaded.value) delay(REFRESH_SETTLE_MS)
            val list = ArrayList<AppEntry>(192)
            val hidden = ArrayList<AppEntry>(16)
            var privateUser: UserHandle? = null
            var privateSerial: Long? = null

            for (user in userManager.userProfiles) {
                val serial = userManager.getSerialNumberForUser(user)
                val isPrivate = isPrivateProfile(user)
                if (isPrivate) {
                    privateUser = user
                    privateSerial = serial
                }

                val activities = runCatching { launcherApps.getActivityList(null, user) }
                    .getOrElse {
                        // Thrown when a profile is locked or has just been
                        // removed. One unreadable profile should cost that
                        // profile's apps, not the whole list.
                        //
                        // The profile is recorded above this query and not
                        // below it, so that a profile whose activities cannot
                        // be read is still known to exist. Otherwise the
                        // launcher would only discover private space at the one
                        // moment it was already open.
                        //
                        // Locking is not that case, as it turns out: a profile
                        // in quiet mode still lists its activities, the same way
                        // a paused work profile does. What changes is that they
                        // cannot be started -- and the platform does not report
                        // that as a failure either.
                        Log.w(TAG, "cannot list activities for $user", it)
                        emptyList()
                    }
                for (info in activities) {
                    val entry = AppEntry(
                        component = info.componentName,
                        user = user,
                        userSerial = serial,
                        label = info.label?.toString().orEmpty().ifEmpty { info.componentName.packageName },
                    )
                    if (isPrivate) hidden += entry else list += entry
                }
            }

            list.sortWith(compareBy(collator) { it.label })
            hidden.sortWith(compareBy(collator) { it.label })
            _apps.value = list
            _privateApps.value = hidden
            _privateProfile.value = privateUser
            _privateSerial.value = privateSerial
            _privateLocked.value = privateUser?.let { quietModeOn(it) } ?: true
            _loaded.value = true
        }
    }

    // ---- private space ---------------------------------------------------

    /**
     * Whether a profile is Android 15's private space.
     *
     * Asked through LauncherApps rather than by reading user properties,
     * because this is the one question the platform added a launcher-facing
     * API for: getLauncherUserInfo answers it directly and needs only the
     * ACCESS_HIDDEN_PROFILES permission a launcher declares anyway. Without
     * that permission the profile is not in userProfiles at all, so this
     * returns false and private space simply does not exist as far as the rest
     * of the launcher is concerned -- which is the correct way to fail.
     */
    private fun isPrivateProfile(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        if (user == Process.myUserHandle()) return false
        return runCatching {
            launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
        }.getOrDefault(false)
    }

    private fun quietModeOn(user: UserHandle): Boolean =
        runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(true)

    /** Re-read the lock state without rebuilding the whole app list. */
    fun refreshPrivateLock() {
        _privateLocked.value = _privateProfile.value?.let { quietModeOn(it) } ?: true
    }

    /**
     * Lock or unlock private space.
     *
     * Quiet mode is the switch, and asking for it to come off is also what
     * raises the system's own credential prompt -- the launcher never sees or
     * handles the PIN, it asks and the platform takes over. The return value
     * says whether the request was accepted at all, not whether the space ended
     * up unlocked; the answer to that arrives later, as a profile broadcast.
     *
     * Needs MODIFY_QUIET_MODE, which comes with the default-launcher role. A
     * launcher that is not the default gets a SecurityException here, which is
     * why the caller is handed a false rather than a crash.
     */
    fun setPrivateSpaceLocked(locked: Boolean): Boolean {
        val user = _privateProfile.value ?: return false
        return runCatching {
            userManager.requestQuietModeEnabled(locked, user)
            true
        }.getOrElse {
            Log.w(TAG, "quiet mode request refused", it)
            false
        }
    }

    /**
     * The system's own private space settings page.
     *
     * An IntentSender rather than an Intent because that is what the platform
     * hands back: the target is a settings screen this app has no business
     * resolving or starting directly.
     */
    fun privateSpaceSettingsIntent(): android.content.IntentSender? =
        if (Build.VERSION.SDK_INT < 35) null
        else runCatching { launcherApps.privateSpaceSettingsIntent }.getOrNull()

    /**
     * Told when a profile is locked or unlocked from anywhere.
     *
     * Registered rather than polled, because the unlock happens in the system's
     * credential prompt and not here: the launcher asks, the user authenticates
     * in another window, and this broadcast is the only notice that it worked.
     * The package callbacks above fire too, but only for apps that became
     * visible -- on a private space with nothing installed yet they never fire
     * at all, and the panel would sit there still showing a lock.
     */
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshPrivateLock()
            refresh()
        }
    }

    /**
     * Ask the system to pin a shortcut to this launcher.
     *
     * Recording the shortcut in the layout is not enough on its own. A dynamic
     * shortcut belongs to the app that published it and may be replaced or
     * withdrawn at any time; pinning is how a launcher tells the system it is
     * holding on to one, and a shortcut that was never pinned can simply stop
     * existing, leaving an icon that fails to start with nothing to explain it.
     *
     * The existing pins are read and passed back with the new one because
     * pinShortcuts replaces the whole set rather than adding to it -- calling
     * it with one id would silently unpin every other shortcut this launcher
     * holds for that app.
     *
     * Only the default launcher may do this; anyone else is refused, which is
     * handed back as false rather than thrown.
     */
    fun pinShortcut(info: ShortcutInfo): Boolean = runCatching {
        val pkg = info.`package`
        val user = info.userHandle
        val query = LauncherApps.ShortcutQuery()
            .setPackage(pkg)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val existing = launcherApps.getShortcuts(query, user).orEmpty().map { it.id }
        launcherApps.pinShortcuts(pkg, (existing + info.id).distinct(), user)
        true
    }.getOrElse {
        Log.w(TAG, "could not pin ${info.id}", it)
        false
    }

    fun serialFor(user: UserHandle): Long =
        runCatching { userManager.getSerialNumberForUser(user) }.getOrDefault(0L)

    fun userFor(serial: Long): UserHandle =
        runCatching { userManager.getUserForSerialNumber(serial) }.getOrNull() ?: Process.myUserHandle()

    /**
     * Every profile's apps, for resolving something rather than listing it.
     *
     * Splitting private space out of [apps] is about what gets *shown*: the
     * drawer, the search results, the folder picker. It was never meant to
     * change what an icon already on the home screen resolves to, and quietly
     * doing both is a regression -- an icon the user placed months ago stopped
     * resolving and fell through to the "no such app" placeholder, with no way
     * to tell that from the app genuinely having been uninstalled.
     *
     * So lookups read this and lists read [apps]. The personal list comes
     * first, so an app that exists in both profiles resolves to the personal
     * copy, which is the one the icon was made from.
     */
    private fun lookupLists(): List<List<AppEntry>> = listOf(_apps.value, _privateApps.value)

    fun entryFor(component: String?, userSerial: Long): AppEntry? {
        if (component == null) return null
        val key = AppEntry.keyOf(component, userSerial)
        val lists = lookupLists()
        return lists.firstNotNullOfOrNull { list -> list.firstOrNull { it.key == key } }
            // Fall back to matching on component alone. An item imported from a
            // backup made on another phone carries that phone's profile serial,
            // which will not match here, and refusing to resolve it would mean
            // a work-profile export lands as a screen full of dead icons.
            ?: lists.firstNotNullOfOrNull { list ->
                list.firstOrNull { it.component.flattenToString() == component }
            }
    }

    fun isInstalled(component: String?, userSerial: Long): Boolean =
        entryFor(component, userSerial) != null

    /**
     * The launcher entry for a package, when the package is all that is known.
     *
     * A shortcut records who published it and nothing more -- there is no
     * component, because the thing that created it is an app, not an activity.
     * So a browser shortcut says `com.brave.browser` and this is what turns
     * that into an icon.
     *
     * An app with several launcher activities resolves to whichever comes
     * first. That is the wrong question to ask precisely: the badge means "this
     * came from Brave", and every one of Brave's entries would answer that.
     */
    fun entryForPackage(packageName: String?, userSerial: Long): AppEntry? {
        if (packageName.isNullOrEmpty()) return null
        val lists = lookupLists()
        return lists.firstNotNullOfOrNull { list ->
            list.firstOrNull { it.packageName == packageName && it.userSerial == userSerial }
        } ?: lists.firstNotNullOfOrNull { list ->
            list.firstOrNull { it.packageName == packageName }
        }
    }

    // ---- launching -------------------------------------------------------

    /**
     * [sourceBounds] is the icon's position on screen. The system passes it to
     * the launched app so its opening animation can grow out of the icon the
     * user actually touched. Omitting it is not an error, it just makes every
     * app open from the middle of nowhere.
     */
    fun launchApp(entry: AppEntry, sourceBounds: Rect?, opts: Bundle? = null): Boolean =
        runCatching {
            launcherApps.startMainActivity(entry.component, entry.user, sourceBounds, opts)
            true
        }.getOrElse {
            Log.w(TAG, "could not start ${entry.component}", it)
            false
        }

    fun launchItem(item: LauncherItem, sourceBounds: Rect?, opts: Bundle? = null): Boolean =
        when (item.type) {
            ItemType.APP -> {
                // The package is a fallback for the component, because an app
                // may change which activity it launches from.
                //
                // An update that renames or replaces the launcher activity
                // leaves the stored component naming something that no longer
                // exists. entryFor then finds nothing, the launch fails, and
                // the caller asks whether the package is installed -- which it
                // is, so nothing is offered and nothing happens. The icon drew
                // correctly the whole time, because the icon already falls back
                // to the package; only the tap was dead, which is the hardest
                // version of this to make sense of from the outside.
                val entry = entryFor(item.component, item.userSerial)
                    ?: entryForPackage(
                        item.packageName ?: item.componentName?.packageName,
                        item.userSerial,
                    )
                if (entry != null) launchApp(entry, sourceBounds, opts) else false
            }

            ItemType.DEEP_SHORTCUT -> startShortcut(item, sourceBounds, opts)

            ItemType.SHORTCUT -> runCatching {
                val intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
                intent.sourceBounds = sourceBounds
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent, opts)
                true
            }.getOrElse {
                Log.w(TAG, "could not start shortcut ${item.title}", it)
                false
            }

            else -> false
        }

    /**
     * Start an app-published shortcut.
     *
     * Only the current default launcher is allowed to do this; anyone else gets
     * a SecurityException. That is worth catching rather than preventing,
     * because the user can change their default launcher while this one is
     * still in memory, and the failure should be a shortcut that does not open
     * rather than a crash on the home screen.
     */
    private fun startShortcut(item: LauncherItem, bounds: Rect?, opts: Bundle?): Boolean {
        val pkg = item.packageName ?: return false
        val id = item.shortcutId ?: return false

        // The recorded profile first, then the others.
        //
        // A pinned shortcut is looked up by profile, and the recorded one can
        // be wrong through no fault of the user: every shortcut pinned before
        // the profile was stored at all carries a zero, and a backup restored
        // from another phone carries that phone's serial numbers. Neither is
        // recoverable from the item itself, and both look identical to the user
        // -- an icon that sits there and does nothing.
        //
        // Trying the rest is cheap, because there are two or three profiles on
        // a phone and this only runs after the first attempt has already
        // failed. A shortcut id is unique within its publishing package, so
        // finding it under another profile cannot start the wrong thing.
        val first = userFor(item.userSerial)
        val others = runCatching { userManager.userProfiles }.getOrDefault(emptyList())
            .filterNot { it == first }

        for (user in listOf(first) + others) {
            val started = runCatching {
                launcherApps.startShortcut(pkg, id, bounds, opts, user)
                true
            }.getOrElse {
                Log.w(TAG, "could not start shortcut $id in $pkg for $user", it)
                false
            }
            if (started) return true
        }
        return false
    }

    fun openAppInfo(entry: AppEntry, bounds: Rect? = null) {
        runCatching { launcherApps.startAppDetailsActivity(entry.component, entry.user, bounds, null) }
            .onFailure { Log.w(TAG, "could not open app info", it) }
    }

    fun uninstall(entry: AppEntry) {
        // ACTION_DELETE rather than the package installer session API: this is
        // a user-facing removal and should show the system's own confirmation,
        // which is the only thing allowed to actually remove an app anyway.
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not start uninstall", it) }
    }

    /**
     * Send the user to the store page for a package that is not installed.
     *
     * An icon outliving its app is normal, not a fault: an app is uninstalled
     * to clear space, or a restored backup names apps this phone has never had.
     * Tapping one of those did nothing at all, which reads as the launcher
     * being broken rather than the app being gone.
     *
     * The market scheme first and the web address second, because they are not
     * the same offer: `market://` opens the installed store app straight on the
     * listing, and the https form is what is left on a device with no store
     * app, where it opens a browser. Both are tried by starting them, not by
     * resolving them first -- resolution is subject to package visibility and
     * would report "no store" on a phone that has one.
     *
     * Returns false when neither could be started, so the caller can say so
     * rather than appearing to have done something.
     */
    fun openInStore(packageName: String): Boolean {
        val uris = listOf(
            "market://details?id=$packageName",
            "https://play.google.com/store/apps/details?id=$packageName",
        )
        for (uri in uris) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { appContext.startActivity(intent) }.isSuccess) return true
        }
        Log.w(TAG, "no store could open $packageName")
        return false
    }

    /**
     * Whether the system will let this app be uninstalled.
     *
     * Not simply "is it a system app". An app that shipped with the phone and
     * has since been updated carries FLAG_SYSTEM *and* FLAG_UPDATED_SYSTEM_APP,
     * and Android does allow uninstalling it -- the update is removed and the
     * factory version comes back. On a real phone that describes most of what
     * is installed: the browser, mail, maps, messages. Testing FLAG_SYSTEM
     * alone therefore hid Uninstall on nearly every app the user had, which is
     * exactly the wrong set to hide it on.
     *
     * The pair below is the test AOSP's own launcher uses for the same
     * decision.
     *
     * Defaults to true when the lookup fails. Offering an uninstall the system
     * then declines is a dialog the user dismisses; withholding one they are
     * entitled to looks like the launcher is broken.
     */
    fun canUninstall(entry: AppEntry): Boolean = runCatching {
        val info = launcherApps.getApplicationInfo(entry.packageName, 0, entry.user)
        val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val updated = info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        !system || updated
    }.getOrDefault(true)

    /**
     * The shortcuts an app publishes, for the long-press menu.
     *
     * Returns empty unless FreeLauncher is the default launcher -- the platform
     * refuses the query otherwise. Callers show whatever comes back, so a
     * non-default launcher simply has a shorter menu instead of an error.
     */
    fun shortcutsFor(entry: AppEntry): List<ShortcutInfo> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setActivity(entry.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        return runCatching { launcherApps.getShortcuts(query, entry.user).orEmpty() }
            .getOrElse {
                Log.w(TAG, "shortcut query refused", it)
                emptyList()
            }
    }

    /**
     * Why a shortcut did not start.
     *
     * Asked only after starting one has already failed. A launcher cannot read
     * a shortcut's intent -- that belongs to the app that published it -- so
     * the only thing it can do about a shortcut that will not open is find out
     * whether the publisher still has it, and say so. "Nothing happened" is the
     * one answer that helps nobody.
     */
    enum class ShortcutState {
        /** The publisher still offers it and it should have worked. */
        AVAILABLE,

        /** The publisher has turned it off, usually with a reason attached. */
        DISABLED,

        /** The publisher no longer has it at all. */
        MISSING,

        /** Not answerable -- most often because this is not the default launcher. */
        UNKNOWN,
    }

    fun describeShortcut(item: LauncherItem): Pair<ShortcutState, String?> {
        val pkg = item.packageName ?: return ShortcutState.UNKNOWN to null
        val id = item.shortcutId ?: return ShortcutState.UNKNOWN to null
        if (!hasShortcutPermission()) return ShortcutState.UNKNOWN to null

        val first = userFor(item.userSerial)
        val others = runCatching { userManager.userProfiles }.getOrDefault(emptyList())
            .filterNot { it == first }

        for (user in listOf(first) + others) {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setShortcutIds(listOf(id))
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                )
            val found = runCatching { launcherApps.getShortcuts(query, user).orEmpty() }
                .getOrDefault(emptyList())
            val info = found.firstOrNull { it.id == id } ?: continue
            return if (info.isEnabled) {
                ShortcutState.AVAILABLE to null
            } else {
                ShortcutState.DISABLED to info.disabledMessage?.toString()
            }
        }
        return ShortcutState.MISSING to null
    }

    fun hasShortcutPermission(): Boolean =
        runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    fun startShortcutInfo(info: ShortcutInfo, bounds: Rect?) {
        runCatching { launcherApps.startShortcut(info, bounds, null) }
            .onFailure { Log.w(TAG, "could not start shortcut", it) }
    }

    /**
     * A shortcut's own icon, badged for the profile that published it.
     *
     * Asked for here rather than by handing the LauncherApps service out,
     * because this is the only thing anyone wanted it for and a shortcut icon
     * has a wrinkle worth keeping in one place: getShortcutIconDrawable returns
     * null for a shortcut whose publisher supplied none, and the answer then is
     * the publishing app's icon rather than a blank square.
     */
    fun shortcutIcon(info: ShortcutInfo, density: Int): android.graphics.drawable.Drawable? =
        runCatching { launcherApps.getShortcutIconDrawable(info, density) }.getOrNull()

    /** Icon for an entry, badged for its profile. */
    fun iconFor(entry: AppEntry, density: Int) = runCatching {
        val info = launcherApps.getActivityList(entry.packageName, entry.user)
            .firstOrNull { it.componentName == entry.component }
        info?.getBadgedIcon(density)
    }.getOrNull()

    companion object {
        private const val TAG = "AppRepository"

        /** How long to wait for a burst of package changes to finish. */
        private const val REFRESH_SETTLE_MS = 180L

        /**
         * Whether this app is the phone's home screen right now.
         *
         * Resolved rather than remembered, because the user can change it from
         * system settings at any time and there is no broadcast for it.
         */
        fun isDefaultLauncher(context: Context): Boolean {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(
                intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            return resolved?.activityInfo?.packageName == context.packageName
        }

        /**
         * Send the user to the place where a default launcher is chosen.
         *
         * Android has never offered a "make me the launcher" API, deliberately.
         * The closest thing is the home settings panel, which exists on most
         * devices; where it does not, falling back to the top-level settings
         * app at least lands the user somewhere they can finish the job.
         */
        fun openHomeSettings(context: Context) {
            val candidates = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                }
                add(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                add(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
            for (intent in candidates) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    runCatching { context.startActivity(intent) }.onSuccess { return }
                }
            }
        }
    }
}
