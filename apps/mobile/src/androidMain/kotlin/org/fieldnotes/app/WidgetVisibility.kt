package org.fieldnotes.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import kotlinx.coroutines.*

/** OS lock state controls disclosure, never encrypted synchronization. */
internal object WidgetVisibility {
    @Volatile private var screenOff=false
    private var observed:Boolean?=null
    private var probe:Job?=null
    fun unlocked(context:Context)=context.getSystemService(UserManager::class.java).isUserUnlocked&&!screenOff&&!context.getSystemService(KeyguardManager::class.java).isDeviceLocked&&!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private suspend fun refresh(context:Context,force:Boolean=false){
        val current=unlocked(context);currentCoroutineContext().ensureActive()
        if(synchronized(this){if(!force&&observed==current)false else{observed=current;true}}){
            PlannerWidget.refresh(context);UpcomingWidget.refresh(context);CalendarWidget.refresh(context)
        }
    }
    fun transition(context:Context,action:String?=null){
        if(action==Intent.ACTION_SCREEN_OFF)screenOff=true
        else if(action==null||action in setOf(Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT,Intent.ACTION_USER_UNLOCKED,Intent.ACTION_USER_FOREGROUND))screenOff=false
        probe?.cancel()
        val app=context.applicationContext as FieldNotesApplication
        // Binder queries and widget publication must not block activity input.
        probe=app.scope.launch(Dispatchers.IO){
            refresh(app,true)
            if(action!=Intent.ACTION_SCREEN_OFF)for(wait in listOf(250L,750L,2000L)){delay(wait);refresh(app)}
        }
    }
}
