package org.fieldnotes.app

import android.content.Context
import android.content.res.Configuration
import org.fieldnotes.domain.Cache
import org.fieldnotes.domain.*
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri

internal fun widgetRecords(cache:Cache)=projectRecords(cache).map { it.record }.filter { cache.preferences.widgetHousehold||reminderRecipient(it,cache.snapshot?.actor?.user_id.orEmpty()) }
internal fun widgetSettings(context:Context)=PendingIntent.getActivity(context,744,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(Uri.parse("fieldnotes-widget://settings/visibility")).putExtra("open_page","Settings"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
internal fun widgetProblem(context:Context):Boolean {
    val recoveryCache=(context.applicationContext as FieldNotesApplication).model.state.value.cache
    if(recoveryCache.recoveryPending!=null||recoveryCache.recoveryNotice.isNotBlank())return true
    val app=context.applicationContext as FieldNotesApplication
    return app.model.state.value.problem!=null || app.deliveryError.value!=null || app.deliveryPersistenceError.value!=null || app.updates.state.value.problem
}
internal fun widgetHiddenLabel(cache:Cache,unlocked:Boolean,authenticated:Boolean)=when {
    !authenticated||cache.token==null||cache.snapshot==null->"Open Field Notes to sign in"
    !unlocked&&!cache.preferences.widgetOnLockScreen->"Unlock phone to show schedule"
    else->"Show schedule"
}

internal data class WidgetPalette(val background:Int,val text:Int,val muted:Int,val accent:Int,val selected:Int,val track:Int)
internal fun widgetPalette(context:Context,cache:Cache):WidgetPalette {
    val dark=cache.preferences.theme=="dark" || cache.preferences.theme=="system"&&context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    return if(dark)WidgetPalette(0xFF1A2432.toInt(),0xFFE8EEF8.toInt(),0xFFAEBDD4.toInt(),0xFFA8B7FF.toInt(),0xFF2B3C69.toInt(),0xFF34445D.toInt())
        else WidgetPalette(0xFFFFFFFF.toInt(),0xFF1D293E.toInt(),0xFF61728B.toInt(),0xFF4D61C9.toInt(),0xFFE0E7FF.toInt(),0xFFDCE3EF.toInt())
}
internal fun blendWidgetColor(from:Int,to:Int,ratio:Double):Int {
    val t=ratio.coerceIn(0.0,1.0)
    fun channel(shift:Int)=((((from shr shift) and 255)*(1-t)+((to shr shift) and 255)*t).toInt() and 255) shl shift
    return 0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
}
