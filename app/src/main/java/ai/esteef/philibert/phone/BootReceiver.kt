package ai.esteef.philibert.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart (or an update of the app), reconnect to Philibert by itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        HandsService.start(c)
    }
}
