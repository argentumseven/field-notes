package org.fieldnotes.app

import android.app.Application
import kotlinx.coroutines.*
import org.fieldnotes.domain.Workspace
import org.fieldnotes.platform.AndroidTransport
import org.fieldnotes.platform.AndroidVault
import java.util.UUID

class FieldNotesApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var transport: AndroidTransport
    lateinit var vault: AndroidVault
    lateinit var model: Workspace
    val initialized = CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate()
        transport = AndroidTransport(); vault = AndroidVault(this)
        model = Workspace(transport, vault, { UUID.randomUUID().toString() }, scope)
        scope.launch { try { model.initialize() } finally { initialized.complete(Unit) } }
    }
}
