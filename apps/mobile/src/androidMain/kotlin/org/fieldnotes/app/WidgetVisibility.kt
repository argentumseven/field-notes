package org.fieldnotes.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.os.PowerManager
import kotlinx.coroutines.*

/** OS lock state controls disclosure, never encrypted synchronization. */
internal object WidgetVisibility {
    private var probe:Job?=null
    fun unlocked(context:Context)=context.getSystemService(UserManager::class.java).isUserUnlocked&&context.getSystemService(PowerManager::class.java).isInteractive&&!context.getSystemService(KeyguardManager::class.java).isDeviceLocked&&!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private suspend fun refresh(context:Context){
        currentCoroutineContext().ensureActive();PlannerWidget.refresh(context)
        currentCoroutineContext().ensureActive();UpcomingWidget.refresh(context)
        currentCoroutineContext().ensureActive();CalendarWidget.refresh(context)
    }
    @Synchronized fun transition(context:Context,action:String?=null){
        probe?.cancel()
        val app=context.applicationContext as FieldNotesApplication
        // Binder queries and widget publication must not block activity input.
        probe=app.scope.launch(Dispatchers.IO){
            app.initialized.await()
            refresh(app)
            if(action!=Intent.ACTION_SCREEN_OFF) {
                var elapsed=0L
                for(wait in listOf(250L,750L,2000L,5000L,10000L,12000L)) {
                    delay(wait);elapsed+=wait
                    // A provider can publish a locked view after the first
                    // observation, including while a new widget is being pinned.
                    refresh(app)
                    if(elapsed>=3000&&unlocked(app))break
                }
            }
        }
    }
}
