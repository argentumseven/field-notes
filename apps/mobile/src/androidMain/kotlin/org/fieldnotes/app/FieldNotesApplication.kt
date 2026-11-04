package org.fieldnotes.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
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
    val updates by lazy { ApkUpdates(this) }
    @Volatile var uiVisible=false
    val deliveryError=MutableStateFlow<String?>(null)
    val deliveryPersistenceError=MutableStateFlow<String?>(null)
    val initialized = CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate()
        transport = AndroidTransport(); vault = AndroidVault(this)
        model = Workspace(transport, vault, { UUID.randomUUID().toString() }, scope)
        scope.launch { try { model.initialize() } finally { initialized.complete(Unit) } }
        scope.launch(Dispatchers.IO) {
            try { AlarmAudio.restore(this@FieldNotesApplication) }catch(_:Exception){deliveryError.value="Previous alarm volume needs review. Open Android sound settings."}
            try {
                val previous=vault.readEffect("delivery-health")
                if(previous!=null){try{if(deliveryError.value==null)deliveryError.value=previous.decodeToString().takeIf {it.isNotBlank()}}finally{previous.fill(0)}}
            }catch(_:Exception){deliveryPersistenceError.value="Delivery status could not be read. Check local storage."}
            deliveryError.collect { message ->
                val bytes=message.orEmpty().take(800).encodeToByteArray()
                try{vault.writeEffect("delivery-health",bytes);deliveryPersistenceError.value=null}
                catch(_:Exception){deliveryPersistenceError.value="Delivery status could not be saved. Free local storage; cached work is retained."}
                finally{bytes.fill(0)}
                try {
                    if(message!=null)Alerts.post(this@FieldNotesApplication,Alerts.ISSUES,"delivery-health","Field Notes needs attention",message)
                    else getSystemService(android.app.NotificationManager::class.java).cancel("field-notes:"+Alerts.digest("delivery-health"),0)
                    CalendarWidget.refresh(this@FieldNotesApplication);UpcomingWidget.refresh(this@FieldNotesApplication);PlannerWidget.refresh(this@FieldNotesApplication)
                }catch(_:Exception){/* The saved issue stays visible in the app even when Android blocks posting. */}
            }
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { if (::model.isInitialized){PlannerWidget.refresh(context);UpcomingWidget.refresh(context);CalendarWidget.refresh(context)} }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_USER_UNLOCKED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // StateFlow conflates new states while this work completes. Do not cancel
        // reconciliation between saving its intent and installing the OS alarms.
        scope.launch { initialized.await(); model.state.collect { delay(600); PlannerWidget.refresh(this@FieldNotesApplication);UpcomingWidget.refresh(this@FieldNotesApplication);CalendarWidget.refresh(this@FieldNotesApplication);try{LocalAlarms.reconcile(this@FieldNotesApplication)}catch(_:Exception){deliveryError.value="Reminder scheduling needs attention. Open Field Notes."} } }
    }
}
