package org.fieldnotes.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/** Dismiss only the issue displayed by this notification, never a newer failure. */
class DeliveryIssueReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val digest=intent.getStringExtra("issue_digest") ?: return
        val app=context.applicationContext as FieldNotesApplication
        val pending=goAsync()
        app.scope.launch {
            try { app.initialized.await();app.dismissDeliveryIssue(digest) }
            finally { pending.finish() }
        }
    }
}
