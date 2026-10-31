package org.fieldnotes.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.fieldnotes.domain.*

/** User-visible, Google-free notification transport. This is not a VPN service. */
class PrivateUpdatesService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work:Job?=null
    private var wake:PowerManager.WakeLock?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        Alerts.channels(this)
        val open=PendingIntent.getActivity(this,91,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,92,Intent(this,PrivateUpdatesService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,Alerts.CONNECTION).setSmallIcon(R.drawable.ic_field_notes)
            .setContentTitle("Field Notes background updates").setContentText("Private connection enabled · no Google services")
            .setContentIntent(open).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0,"Stop background mode",stop).build()
        startForeground(90,notification)
        val app=application as FieldNotesApplication
        if(intent?.action=="stop"){
            work?.cancel()
            scope.launch {
                try {
                    app.initialized.await()
                    app.model.preferences(app.model.state.value.cache.preferences.copy(liveUpdates=false))
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) {
                    app.deliveryError.value="Background mode stopped, but its preference could not be saved. Free storage and review settings before restarting."
                } finally { stopSelf() }
            }
            return START_NOT_STICKY
        }
        if(work?.isActive!=true)work=scope.launch {
            app.initialized.await()
            var retry=15_000L
            while(isActive){
                val before=app.model.state.value
                if(!before.ready||before.needsLogin||before.cache.token==null||!before.cache.preferences.liveUpdates){stopSelf();break}
                // A bounded lock supports screen-off delivery while live mode is
                // healthy. It cannot override stopped users, force-stop or DND.
                if(wake==null)wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"fieldnotes:private-updates").also { it.setReferenceCounted(false) }
                wake?.acquire(120_000)
                val changed=app.model.poll()
                val after=app.model.state.value
                if(changed&&after.online&&!after.needsLogin){
                    val old=before.cache.snapshot?.records.orEmpty().associateBy { it.id }
                    val actor=after.cache.snapshot?.actor?.user_id
                    val relevant=after.cache.snapshot?.records.orEmpty().any { r->
                        r.revision!=(old[r.id]?.revision?:0L)&&r.data.text("updated_by").let { it.isNotBlank()&&it!=actor }&&
                            (r.kind!="task"||r.data.text("assignee").let { it.isBlank()||it==actor })
                    }
                    if(relevant&&!app.uiVisible){val snap=after.cache.snapshot!!;Alerts.post(this@PrivateUpdatesService,Alerts.UPDATES,"changes:${snap.epoch}:${snap.generation}:${snap.sequence}","Your household has updates","Open Field Notes to see the latest assignments and changes.")}
                }
                try { LocalAlarms.reconcile(this@PrivateUpdatesService) } catch(_:Exception){app.deliveryError.value="Reminder scheduling needs attention. Open Field Notes."}
                if(after.online){retry=15_000;delay(retry)}else{wake?.takeIf { it.isHeld }?.release();retry=(retry*2).coerceAtMost(300_000);delay(retry)}
            }
        }
        return START_STICKY
    }
    override fun onDestroy(){work?.cancel();scope.cancel();wake?.takeIf { it.isHeld }?.release();super.onDestroy()}
    companion object {
        fun start(context:Context):Boolean {
            if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
            return try { ContextCompat.startForegroundService(context,Intent(context,PrivateUpdatesService::class.java));true }
            catch(_:IllegalStateException){false}catch(_:SecurityException){false}
        }
        fun stop(context:Context){
            // Android matches service intents by component, not object identity.
            val serviceIntent=Intent(context,PrivateUpdatesService::class.java)
            context.stopService(serviceIntent)
        }
    }
}

class StartupReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED&&intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED)return
        val pending=goAsync();val app=context.applicationContext as FieldNotesApplication
        app.scope.launch {
            try {
                val cache=app.vault.load()
                if(cache.token!=null&&cache.preferences.liveUpdates&&!PrivateUpdatesService.start(context))app.deliveryError.value="Open Field Notes to restart background updates."
                CatchUpWorker.schedule(context,cache.preferences)
                LocalAlarms.reconcile(context,true)
            }catch(_:Exception){app.deliveryError.value="Open Field Notes to restore reminder scheduling."}
            finally{pending.finish()}
        }
    }
}
