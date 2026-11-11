package ai.esteef.philibert.phone

import android.service.notification.NotificationListenerService

/** Lets Philibert read the phone's notifications when asked ("what did I miss?"). */
class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile var me: NotifListener? = null
    }

    override fun onListenerConnected() { me = this }
    override fun onListenerDisconnected() { me = null }
}
