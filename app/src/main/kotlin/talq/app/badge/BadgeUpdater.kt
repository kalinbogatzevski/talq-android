package talq.app.badge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * One way of putting a number on the launcher icon. Android itself has none (a launcher may
 * show a dot or count notifications); some phone makers' launchers take a number directly.
 * Add another maker by implementing this and listing it in [UnreadBadge].
 */
interface BadgeUpdater {
    /** This phone has the launcher this updater talks to. Must never throw. */
    fun isAvailable(context: Context): Boolean

    /**
     * Show [count] on the icon; 0 removes the number. False when the launcher refused. Must
     * never throw. A call into another process: not for the main thread.
     */
    fun set(context: Context, count: Int): Boolean
}

/**
 * The badge call shared by Honor's and Huawei's launchers: the launcher's settings provider
 * takes `change_badge` with the app's package, its launcher activity and the number.
 * Needs the launcher's `CHANGE_BADGE` permission (declared in the manifest; granted at
 * install) and, on Android 11+, a `<queries>` entry so the launcher can be seen at all.
 */
class LauncherProviderBadge(private val launcherPackage: String) : BadgeUpdater {
    private val uri: Uri = Uri.parse("content://$launcherPackage.settings/badge/")

    override fun isAvailable(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(launcherPackage, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** The app's launcher activity, looked up once. */
    @Volatile
    private var launcherActivity: String? = null

    @Volatile
    private var failureLogged = false

    override fun set(context: Context, count: Int): Boolean = try {
        val activity = launcherActivity
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.className
                ?.also { launcherActivity = it }
        if (activity == null) {
            refused("no launcher activity found", null)
        } else {
            val extras = Bundle().apply {
                putString("package", context.packageName)
                putString("class", activity)
                putInt("badgenumber", count.coerceIn(0, 9999))
            }
            context.contentResolver.call(uri, "change_badge", null, extras)
            true
        }
    } catch (e: Exception) {
        // No such provider on this launcher version, or the permission was not granted.
        refused("change_badge failed", e)
    }

    /** Logged once per process: enough to see it on the phone, no spam where it never works. */
    private fun refused(why: String, e: Exception?): Boolean {
        if (!failureLogged) {
            failureLogged = true
            Log.w("TalqBadge", "$launcherPackage: $why", e)
        }
        return false
    }

    companion object {
        val Honor = LauncherProviderBadge("com.hihonor.android.launcher")
        val Huawei = LauncherProviderBadge("com.huawei.android.launcher")
    }
}
