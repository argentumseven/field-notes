package org.fieldnotes.app

import android.content.Context
import android.content.res.Configuration
import org.fieldnotes.domain.Cache

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
