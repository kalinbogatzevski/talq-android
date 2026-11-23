package talq.app.update

import android.content.Context

/** The generic build has no update channel of its own: its stores update it. */
@Suppress("UNUSED_PARAMETER")
internal fun newAppUpdates(context: Context): AppUpdates = NoAppUpdates
