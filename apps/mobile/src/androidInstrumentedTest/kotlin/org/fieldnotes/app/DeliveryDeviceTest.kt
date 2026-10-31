package org.fieldnotes.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Android service, alarm receiver and notification channels; no Google
 * services. This cannot establish GrapheneOS cross-user forwarding or acoustics. */
class DeliveryDeviceTest {
    @Test fun backgroundServiceAndCachedAlarmPostPrivateAudibleChannelAlerts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FieldNotesApplication
        runBlocking { app.initialized.await() }
        val original = app.model
        val originalCache = original.state.value.cache
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("pm grant org.fieldnotes.app ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("appops set org.fieldnotes.app SCHEDULE_EXACT_ALARM allow")
        val now = Clock.System.now().epochSeconds
        val record = Record("alarm-event", "event", 1, obj("title" to "PRIVATE synthetic calendar title".j(), "starts_at" to (now+615).j(), "ends_at" to (now+3600).j()))
        val snapshot = Snapshot(1,"delivery-test",1,1,listOf(record),Actor("owner","Owner","owner","session"))
        val seed = Cache(keys=originalCache.keys,config=obj("server_key" to "synthetic-delivery-server".j()),token="PRIVATE synthetic token",snapshot=snapshot,
            preferences=Preferences(backgroundSync=false,reminders=true,liveUpdates=true))
        val transport = object: Transport {
            override suspend fun keys() = originalCache.keys!!
            override suspend fun configure(configuration: JsonObject) {}
            override suspend fun stop() {}
            override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?) = when(path) {
                "/api/head" -> Reply(200,obj("epoch" to snapshot.epoch.j(),"generation" to 1.j(),"sequence" to 1.j()).toString())
                else -> error("Local reminders must not fetch content")
            }
        }
        runBlocking { app.vault.save(seed) }
        val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},app.scope)
        runBlocking { model.initialize() }; app.model=model
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        fun waitFor(message:String,timeout:Long=80_000,condition:()->Boolean) {
            val end=System.currentTimeMillis()+timeout
            while(System.currentTimeMillis()<end) { if(condition())return;Thread.sleep(250) }
            error(message)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertTrue(PrivateUpdatesService.start(it)) }
                waitFor("Foreground connection status was not posted") { manager.activeNotifications.any { it.id==90 } }
                val ongoing=manager.activeNotifications.first { it.id==90 }.notification
                assertTrue(ongoing.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertEquals(Notification.VISIBILITY_SECRET,ongoing.visibility)
                assertNull(manager.getNotificationChannel(Alerts.CONNECTION).sound)
                val channel=manager.getNotificationChannel(Alerts.REMINDERS)
                assertEquals(NotificationManager.IMPORTANCE_HIGH,channel.importance)
                assertNotNull(channel.sound)
                // Leave the app: the scheduled broadcast, not UI polling, posts.
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                runBlocking { LocalAlarms.reconcile(app,true) }
                waitFor("Cached calendar alarm was not delivered by Android") { manager.activeNotifications.any { it.notification.channelId==Alerts.REMINDERS && it.tag?.startsWith("field-notes:")==true } }
                val alert=manager.activeNotifications.single { it.notification.channelId==Alerts.REMINDERS && it.tag?.startsWith("field-notes:")==true }
                assertEquals(Notification.VISIBILITY_PRIVATE,alert.notification.visibility)
                assertNotNull(alert.notification.publicVersion)
                val text=alert.notification.extras.toString()
                assertFalse(text.contains("PRIVATE synthetic"))
                assertEquals("Field Notes reminder",alert.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertTrue(File(app.noBackupFilesDir,"vault-v1/effect-alerts.encrypted").exists())
                assertFalse(File(app.noBackupFilesDir,"vault-v1/effect-alerts.encrypted").readBytes().toString(Charsets.ISO_8859_1).contains("event:alarm-event"))
                val tag=alert.tag
                runBlocking { LocalAlarms.fire(app,"event:alarm-event:${now+615}");LocalAlarms.reconcile(app,true) }
                assertEquals(listOf(tag),manager.activeNotifications.filter { it.notification.channelId==Alerts.REMINDERS && it.tag?.startsWith("field-notes:")==true }.map { it.tag })
                assertTrue(Alerts.scheduleProfileTest(app))
                waitFor("Delayed profile-test alarm was not delivered") { manager.activeNotifications.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()=="Field Notes profile test" } }
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                waitFor("System notification shade did not open") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.systemui" }
                Thread.sleep(1500)
                val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val out=File(app.filesDir,"gallery/23-android-notifications.png").apply { parentFile!!.mkdirs() }
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                // Exercise the actual notification Stop action's service path
                // with a disk failure, rather than merely testing preferences.
                val full=object:Vault {
                    override suspend fun load()=seed
                    override suspend fun save(cache:Cache){error("Disk is full")}
                    override suspend fun clearImages(){}
                }
                val failing=Workspace(transport,full,{UUID.randomUUID().toString()},app.scope)
                runBlocking { failing.initialize() };app.model=failing;app.deliveryError.value=null
                app.startService(android.content.Intent(app,PrivateUpdatesService::class.java).setAction("stop"))
                waitFor("Stop failed to report the rejected preference write") { app.deliveryError.value?.contains("could not be saved")==true }
                waitFor("Stop must remove the foreground service despite a disk failure") { manager.activeNotifications.none { it.id==90 } }
            }
        } finally {
            PrivateUpdatesService.stop(app)
            runBlocking { LocalAlarms.clear(app);app.vault.clearEffects();app.vault.save(originalCache) }
            manager.cancelAll();app.model=original
        }
    }
}
