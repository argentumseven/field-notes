package org.fieldnotes.app

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonPrimitive
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Test

class WidgetDeviceTest {
    @Test fun actualRemoteViewsRedactByDefaultAndNeverIncludeNotesOrCredentials() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val task = Record("task", "task", 1, obj("title" to "Replace the filter".j(), "notes" to "PRIVATE-NOTES".j(), "status" to "todo".j(), "priority" to 2.j()))
        val cache = Cache(token="PRIVATE-SESSION",keys=obj("private_key" to "PRIVATE-KEY".j()),snapshot=Snapshot(1,"fixture",1,0,listOf(task),Actor("user","Owner","owner","session")))
        fun allText(view: View): String = when(view) { is TextView -> view.text.toString(); is ViewGroup -> (0 until view.childCount).joinToString(" ") { allText(view.getChildAt(it)) }; else -> "" }
        instrumentation.runOnMainSync {
            fun text(c:Cache, unlocked:Boolean, authenticated:Boolean=true) = allText(PlannerWidget.views(context,c,unlocked,authenticated).apply(context,FrameLayout(context)))
            assertFalse(text(cache,true).contains("Replace the filter"))
            val opted = cache.copy(preferences=Preferences(widgetTitles=true))
            assertTrue(text(opted,true).contains("Replace the filter"))
            assertFalse(text(opted,false).contains("Replace the filter"))
            assertFalse(text(opted,true,false).contains("Replace the filter"))
            assertFalse(text(opted.copy(token=null),true).contains("Replace the filter"))
            for(secret in listOf("PRIVATE-NOTES","PRIVATE-SESSION","PRIVATE-KEY")) assertFalse(text(opted,true).contains(secret))
        }
    }
}
