package talq.app.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Edge to edge with bar icons that read on the chosen theme: dark icons on Paper, light ones
 * on the dark themes, whatever the phone's own light/dark setting is. The navigation bar keeps
 * AndroidX's default scrims for three-button navigation.
 */
@Composable
fun ThemedSystemBars(activity: ComponentActivity, light: Boolean) {
    DisposableEffect(light) {
        val status = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
        val navigation = if (light) SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM) else SystemBarStyle.dark(DARK_SCRIM)
        activity.enableEdgeToEdge(statusBarStyle = status, navigationBarStyle = navigation)
        onDispose {}
    }
}

// AndroidX's own defaults for the navigation bar scrim.
private val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
