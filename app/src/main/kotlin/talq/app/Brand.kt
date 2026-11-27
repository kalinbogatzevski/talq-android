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

/**
 * The server a sign-in must use: built in (branded build) or the organisation's (organisation mode,
 * 0.11.0); null = the person types one. Read it again each time: the organisation can change.
 */
val AppContainer.fixedServer: String?
    get() = if (Brand.isBranded) Brand.serverUrl else organisation.state.value?.server

/** The ERP address in force without typing: built in, or the organisation's; null = the person types one. */
val AppContainer.fixedErp: String?
    get() = if (Brand.isBranded) Brand.erpBaseUrl else organisation.state.value?.erp

/** The product name inside a composable. */
@Composable
fun appName(): String = stringResource(R.string.app_name)
