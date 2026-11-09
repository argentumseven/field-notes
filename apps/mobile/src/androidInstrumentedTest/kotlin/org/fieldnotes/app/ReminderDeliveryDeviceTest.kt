package org.fieldnotes.app

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual Android channels, bundled resources and durable replay protection. */
class ReminderDeliveryDeviceTest {
    @Test fun independentProfilesPostOnceAndTheOfflineScheduleRefills()=runBlocking {
        assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication
        app.initialized.await()
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val now=Clock.System.now();val zone=TimeZone.currentSystemDefault();val day=now.toLocalDateTime(zone).date
        val rules=ReminderSet(listOf(ReminderAlert("bell",0,importance="urgent",sound="bell",volume=100),ReminderAlert("quiet",1,importance="quiet")),
            ReminderWindow("window",weekdays=(1..7).toList(),starts_on=day.toString(),from="00:00",until="00:00",every_minutes=1,zone=zone.id))
        val event=Record("reminder-fixture","event",1,obj("title" to "Home maintenance check-in".j(),"location" to "Kitchen workbench".j(),"starts_at" to (now.epochSeconds-2).j(),"notes" to "Bring the filter measurements and the maintenance checklist. Meet in the kitchen.".j(),"created_by" to "owner".j(),"created_by_name" to "Morgan".j(),"updated_by_name" to "Sam".j(),"created_at" to (now.epochSeconds-3600).j(),"updated_at" to (now.epochSeconds-60).j(),"reminders" to reminderData(rules),"reminder_options" to reminderOptionsData(ReminderOptions(ReminderAudience("people",listOf("owner"))))))
        val seed=app.model.state.value.cache.copy(config=obj("server_key" to "synthetic-reminders".j()),token="synthetic-token",outbox=emptyList(),drafts=emptyMap(),conflicts=emptyList(),
            snapshot=Snapshot(1,"reminder-fixture",1,1,listOf(event),Actor("owner","Morgan","owner","session")),
            preferences=Preferences(connectionPaused=true,backgroundSync=false,liveUpdates=false,reminders=true))
        app.vault.save(seed);app.model.initialize();LocalAlarms.clear(app);LocalAlarms.reconcile(app,true)
        app.deliveryError.value=null
        fun receipts()=app.vault.let { vault -> runBlocking { val bytes=vault.readEffect("alerts")!!;try { WireJson.parseToJsonElement(bytes.decodeToString()).jsonObject } finally { bytes.fill(0) } } }
        val key=customAlerts(event,now,zone).first { it.profile?.sound=="bell" }.key
        val deadline=System.currentTimeMillis()+90_000
        while(System.currentTimeMillis()<deadline&&manager.activeNotifications.none { it.notification.channelId=="fn-reminder-v1-urgent-bell-100-true" }) Thread.sleep(250)
        // Connection/health notices have independent titles; inspect the actual
        // reminder channels rather than a notification left by another fixture.
        val posted=manager.activeNotifications.filter { it.tag?.startsWith("field-notes:")==true&&it.notification.channelId.startsWith("fn-reminder-v1-") }
        assertTrue(posted.any { it.notification.channelId=="fn-reminder-v1-urgent-bell-100-true" })
        assertTrue(posted.any { it.notification.channelId=="fn-reminder-v1-quiet" })
        assertTrue("Posted channels/titles: "+posted.map { it.notification.channelId+": "+it.notification.extras.getString("android.title") },posted.all { it.notification.extras.getString("android.title")=="Home maintenance check-in" })
        assertTrue(posted.all { it.notification.extras.getString("android.bigText")!!.contains("start only") })
        assertTrue(posted.all { it.notification.extras.getString("android.bigText")!!.contains("For Morgan") })
        assertTrue(posted.all { it.notification.publicVersion.extras.getString("android.title")=="Field Notes alert" })
        assertTrue(posted.all { it.notification.extras.getString("android.text")!!.contains("Kitchen workbench") })
        assertTrue(posted.all { it.notification.extras.getString("android.text")!!.contains("Bring the filter measurements") })
        assertTrue(posted.all { it.notification.actions.any { action->action.title.toString()=="Open item" } })
        val urgent=manager.getNotificationChannel("fn-reminder-v1-urgent-bell-100-true")
        assertEquals(NotificationManager.IMPORTANCE_HIGH,urgent.importance)
        assertTrue(urgent.sound.toString().endsWith("/raw/reminder_bell_100"))
        assertNull(manager.getNotificationChannel("fn-reminder-v1-quiet").sound)
        for(tone in reminderTones.keys)for(level in listOf(25,50,75,100)) {
            val id=app.resources.getIdentifier("reminder_${tone}_${level}","raw",app.packageName)
            app.resources.openRawResource(id).use { assertTrue(it.available()>44) }
        }
        // Notification posting precedes the durable receipt/refill transaction.
        // Wait through the coordinator instead of racing its asynchronous IO.
        val receiptDeadline=System.currentTimeMillis()+90_000
        while(System.currentTimeMillis()<receiptDeadline&&receipts().getValue("delivered").jsonObject.isEmpty())Thread.sleep(250)
        val after=receipts()
        assertTrue("Receipt state: $after; ready=${app.model.state.value.ready}; login=${app.model.state.value.needsLogin}; rules="+reminderSet(app.model.state.value.cache.snapshot!!.records.single()),after.getValue("delivered").jsonObject.isNotEmpty())
        assertTrue(after.getValue("scheduled").jsonObject.values.any { it.jsonPrimitive.long>now.epochSeconds })
        val originalTags=posted.map { it.tag }.toSet()
        manager.cancelAll();LocalAlarms.fire(app,key)
        assertTrue("Retry must not replay a delivered occurrence",manager.activeNotifications.none { it.tag in originalTags })
        LocalAlarms.clear(app)
        app.model.preferences(seed.preferences.copy(reminders=false))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        // Keep one representative detailed card for the documentation capture.
        // Delivery of every independent profile was already asserted above.
        val detailed=posted.single {it.notification.channelId=="fn-reminder-v1-urgent-bell-100-true"}
        manager.cancelAll();manager.notify(detailed.tag,detailed.id,detailed.notification)
        // Attach the fixture to a visible normal task before opening System UI,
        // just as a person would when reviewing their reminder settings.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse")).use {it.readBytes()}
        app.startActivity(android.content.Intent(app,MainActivity::class.java).putExtra("open_page","Tasks").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val appDeadline=System.currentTimeMillis()+90_000
        while(System.currentTimeMillis()<appDeadline&&instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()!=app.packageName)Thread.sleep(250)
        assertEquals(app.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications")).use {it.readBytes()}
        fun hasCard():Boolean {
            fun contains(node:android.view.accessibility.AccessibilityNodeInfo):Boolean {
                if(node.isVisibleToUser&&(node.text?.contains("Home maintenance check-in")==true||node.contentDescription?.contains("Home maintenance check-in")==true))return true
                return (0 until node.childCount).any { node.getChild(it)?.let(::contains)==true }
            }
            return instrumentation.uiAutomation.windows.any {it.root?.let {r->r.packageName?.toString()=="com.android.systemui"&&contains(r)}==true}
        }
        val panelDeadline=System.currentTimeMillis()+90_000
        while(System.currentTimeMillis()<panelDeadline) {
            if(hasCard())break
            Thread.sleep(250)
        }
        val panel=instrumentation.uiAutomation.rootInActiveWindow
        if(!hasCard()) {
            java.io.File(app.filesDir,"calendar-gallery").mkdirs()
            instrumentation.uiAutomation.takeScreenshot().also {bitmap->java.io.File(app.filesDir,"calendar-gallery/notification-panel-failure.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            error("Notification panel did not reveal the title; active="+panel?.packageName+"; posted="+manager.activeNotifications.map {it.notification.extras.getString("android.title")})
        }
        fun panelNodes():List<android.view.accessibility.AccessibilityNodeInfo> {
            val nodes=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node:android.view.accessibility.AccessibilityNodeInfo){nodes+=node;for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)}
            instrumentation.uiAutomation.windows.forEach {it.root?.takeIf {r->r.packageName?.toString()=="com.android.systemui"}?.let(::visit)}
            return nodes
        }
        if(panelNodes().none {it.isVisibleToUser&&it.text?.contains("Bring the filter measurements")==true}) {
            fun bounds(node:android.view.accessibility.AccessibilityNodeInfo)=android.graphics.Rect().also {node.getBoundsInScreen(it)}
            val nodes=panelNodes()
            val title=nodes.filter {it.isVisibleToUser&&it.text?.contains("Home maintenance check-in")==true}.minByOrNull {bounds(it).height()} ?: error("Visible reminder title is missing")
            val expand=nodes.filter {it.isVisibleToUser&&it.isClickable&&(it.viewIdResourceName?.contains("expand_button")==true||it.contentDescription?.contains("Expand",ignoreCase=true)==true)}.minByOrNull {kotlin.math.abs(bounds(it).centerY()-bounds(title).centerY())} ?: error("Notification expansion control is missing")
            assertTrue(expand.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            val expandedDeadline=System.currentTimeMillis()+30_000
            while(System.currentTimeMillis()<expandedDeadline&&panelNodes().none {it.isVisibleToUser&&it.text?.contains("Bring the filter measurements")==true})Thread.sleep(250)
        }
        assertTrue("Expanded reminder must show its notes",panelNodes().any {it.isVisibleToUser&&it.text?.contains("Bring the filter measurements")==true})
        Thread.sleep(1500)
        java.io.File(app.filesDir,"calendar-gallery").mkdirs()
        instrumentation.uiAutomation.takeScreenshot().also { bitmap->java.io.File(app.filesDir,"calendar-gallery/43-detailed-notifications.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        manager.cancelAll()
    }
}
