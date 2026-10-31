package org.fieldnotes.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import org.fieldnotes.domain.*
import org.fieldnotes.platform.AndroidVault
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Two separate installations, orchestrated only by the guarded disposable AVD
 * script. The old ciphertext is explicitly reintroduced as a test fixture. */
class UninstallDeviceTest {
    @Test fun normalUninstallDeletesTheProfileKeyAndAppOwnedState() = runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("uninstallPhase")
        assumeTrue("Run through test-android-uninstall.mjs",phase in listOf("before","after"))
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        app.initialized.await()
        val alias="org.fieldnotes.vault.uninstall-probe"
        val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(phase=="before") {
            val vault=AndroidVault(app,"uninstall-probe")
            vault.save(Cache(token="synthetic uninstall secret",preferences=Preferences(backgroundSync=false,reminders=false,liveUpdates=false)))
            vault.writeEffect("probe","synthetic private receipt".toByteArray())
            assertTrue(keys.containsAlias(alias))
            val now=kotlinx.datetime.Clock.System.now().epochSeconds
            val event=Record("uninstall-event","event",1,obj("title" to "Synthetic cleanup event".j(),"starts_at" to (now+3600).j(),"ends_at" to (now+7200).j()))
            // The external harness launches this cached offline workspace after
            // instrumentation exits, then checks live work before uninstall.
            app.vault.save(app.model.state.value.cache.copy(config=obj("server_key" to "synthetic-uninstall-server".j()),token="synthetic uninstall session",
                snapshot=Snapshot(1,"uninstall-fixture",1,1,listOf(event),Actor("owner","Owner","owner","session")),
                preferences=Preferences(backgroundSync=true,reminders=true,liveUpdates=false)))
            CatchUpWorker.schedule(app,Preferences(backgroundSync=true,reminders=true,liveUpdates=false))
            assertTrue(Alerts.scheduleProfileTest(app))
        } else {
            assertFalse("Old installation Keystore alias survived uninstall",keys.containsAlias(alias))
            assertFalse(File(app.noBackupFilesDir,"vault-uninstall-probe").exists())
            assertNull(app.model.state.value.cache.token)
            assertNull(app.model.state.value.cache.config)
            val ciphertext=File(app.filesDir,"old-uninstall-fixture.encrypted").readBytes()
            val vault=AndroidVault(app,"uninstall-probe")
            val folder=File(app.noBackupFilesDir,"vault-uninstall-probe")
            File(folder,"workspace.encrypted").writeBytes(ciphertext)
            try { vault.load();fail("An old installation's ciphertext was accepted") }
            catch(e:IllegalStateException) { assertTrue(e.message.orEmpty().contains("encryption key is missing")) }
            finally { folder.deleteRecursively();File(app.filesDir,"old-uninstall-fixture.encrypted").delete() }
        }
    }
}
