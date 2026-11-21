package talq.app.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import talq.app.appName

private const val PREFS = "talq_push"
private const val KEY_ASKED = "notification_permission_asked"

/**
 * Android 13+: ask for the notification permission once, after sign-in, with a line saying
 * why. Place it anywhere in the signed-in UI; it draws nothing once it has asked. A later
 * change of mind goes through the system settings (the Notifications row in settings).
 */
@Composable
fun NotificationPermissionPrompt() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var show by remember { mutableStateOf(shouldAsk(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (!show) return

    fun done() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
        show = false
    }

    AlertDialog(
        onDismissRequest = { done() },
        title = { Text("Notifications") },
        text = { Text("${appName()} can tell you about new messages and mentions while it is closed. Android asks for your permission first.") },
        confirmButton = {
            TextButton(onClick = {
                done()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = { done() }) { Text("Not now") } },
    )
}

private fun shouldAsk(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return false
    return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
}
