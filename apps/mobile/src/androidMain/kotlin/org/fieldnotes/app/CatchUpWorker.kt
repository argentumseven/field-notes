package org.fieldnotes.app

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import org.fieldnotes.domain.*

/** Recovery fallback; timely cached reminders use AlarmManager, while the
 * user-visible service receives live changes without Google push. */
class CatchUpWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result {
        val app=applicationContext as FieldNotesApplication
        app.initialized.await()
        val state=app.model.state.value
        if(!state.ready)return Result.retry()
        val prefs=state.cache.preferences
        if(prefs.backgroundSync||prefs.liveUpdates)app.model.poll()
        try{LocalAlarms.reconcile(applicationContext,true)}catch(_:Exception){app.deliveryError.value="Reminder scheduling needs attention.";return Result.retry()}
        PlannerWidget.refresh(applicationContext);UpcomingWidget.refresh(applicationContext)
        return Result.success()
    }
    companion object {
        fun schedule(context:Context,preferences:Preferences){
            val work=WorkManager.getInstance(context)
            if(!preferences.backgroundSync&&!preferences.reminders&&!preferences.liveUpdates){work.cancelUniqueWork("field-notes-catch-up");return}
            work.enqueueUniquePeriodicWork("field-notes-catch-up",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CatchUpWorker>(15,TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}
