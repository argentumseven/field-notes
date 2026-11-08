package org.fieldnotes.app
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
class WidgetSyncDeviceTest {
 @Test fun hiddenScheduleAndAppLockDoNotPreventRealWidgetWorkFromSyncing()=runBlocking {
  assumeTrue("Synthetic disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FieldNotesApplication;app.initialized.await()
  val original=app.model;val old=app.vault.load();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val now=Clock.System.now().epochSeconds
  val record=Record(UUID.randomUUID().toString(),"event",1,obj("title" to "Incoming household appointment".j(),"starts_at" to (now+3600).j(),"ends_at" to (now+7200).j()))
  val snapshot=Snapshot(1,"widget-sync-test",1,2,listOf(record),Actor("owner","Owner","owner","session"))
  val cache=Cache(keys=old.keys,config=obj("server_key" to "synthetic-widget-sync".j()),token="synthetic",snapshot=snapshot.copy(sequence=1,records=emptyList()),preferences=Preferences(backgroundSync=false,liveUpdates=false,reminders=false,sharedAdditions=false,widgetTitles=false,appLock=true,autoDownloadUpdates=false))
  val transport=object:Transport {
   override suspend fun keys()=error("Identity must not change")
   override suspend fun configure(configuration:JsonObject){}
   override suspend fun stop(){}
   override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply=Reply(200,when(path){
    "/api/head"->obj("epoch" to snapshot.epoch.j(),"generation" to 1.j(),"sequence" to 2.j()).toString()
    "/api/snapshot"->WireJson.encodeToJsonElement(snapshot).toString()
    "/api/people"->"{\"people\":[],\"revision\":0}"
    else->error("Unexpected fixture route $path")
   })
  }
  try {
   app.vault.save(cache);val model=Workspace(transport,app.vault,{UUID.randomUUID().toString()},scope);model.initialize();app.model=model
   val hidden=CalendarWidget.views(app,cache,true,interactive=true).apply(app,FrameLayout(app))
   assertTrue(hidden.findViewById<TextView>(R.id.calendar_refresh).hasOnClickListeners())
   assertTrue(hidden.findViewById<TextView>(R.id.calendar_refresh).text.toString().contains("sync",true))
   WidgetRefreshWorker.enqueue(app,false)
   withTimeout(90_000){while(model.state.value.cache.snapshot?.sequence!=2L)delay(250)}
   assertEquals("Incoming household appointment",app.vault.load().snapshot!!.records.single().data.text("title"));assertEquals(cache.keys,app.vault.load().keys)
   assertFalse(CalendarWidget.views(app,model.state.value.cache,true).apply(app,FrameLayout(app)).findViewById<TextView>(R.id.calendar_agenda).text.contains("Incoming"))
   model.preferences(model.state.value.cache.preferences.copy(widgetTitles=true))
   assertTrue(CalendarWidget.views(app,model.state.value.cache,true).apply(app,FrameLayout(app)).findViewById<TextView>(R.id.calendar_agenda).text.contains("Incoming"))
  } finally {WorkManager.getInstance(app).cancelUniqueWork("field-notes-widget-refresh").result.get();app.model=original;scope.cancel();app.vault.save(old)}
 }
}
