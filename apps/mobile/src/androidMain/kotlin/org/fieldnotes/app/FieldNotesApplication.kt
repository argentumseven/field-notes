package org.fieldnotes.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import org.fieldnotes.domain.Workspace
import org.fieldnotes.platform.AndroidTransport
import org.fieldnotes.platform.AndroidVault
import java.util.UUID

class FieldNotesApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var transport: AndroidTransport
    lateinit var vault: AndroidVault
    lateinit var model: Workspace
    @Volatile var uiVisible=false
    val deliveryError=MutableStateFlow<String?>(null)
    val initialized = CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate()
        transport = AndroidTransport(); vault = AndroidVault(this)
        model = Workspace(transport, vault, { UUID.randomUUID().toString() }, scope)
        scope.launch { try { model.initialize() } finally { initialized.complete(Unit) } }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { if (::model.isInitialized){PlannerWidget.refresh(context);UpcomingWidget.refresh(context)} }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_USER_UNLOCKED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { initialized.await(); model.state.collectLatest { delay(600); PlannerWidget.refresh(this@FieldNotesApplication);UpcomingWidget.refresh(this@FieldNotesApplication);try{LocalAlarms.reconcile(this@FieldNotesApplication)}catch(_:Exception){deliveryError.value="Reminder scheduling needs attention. Open Field Notes."} } }
    }
}
