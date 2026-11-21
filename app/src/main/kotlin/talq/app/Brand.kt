package talq.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * What differs between the generic app and a branded build (the `brand` flavor dimension in
 * `app/build.gradle.kts`). The values come from the private brand store at build time; the
 * generic build carries empty strings.
 */
object Brand {
    val isBranded: Boolean = BuildConfig.IS_BRANDED

    /** Prefilled on the login screen when no server was remembered. Empty in the generic build. */
    val serverUrl: String = BuildConfig.BRAND_SERVER_URL

    /** The organisation's ERP base for pairing and shift status. Empty in the generic build. */
    val erpBaseUrl: String = BuildConfig.BRAND_ERP_BASE_URL

    /** The name the app calls itself: the one place the product name comes from. */
    fun appName(context: Context): String = context.getString(R.string.app_name)
}

/** The product name inside a composable. */
@Composable
fun appName(): String = stringResource(R.string.app_name)
