@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import org.fieldnotes.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import java.io.File

class PlannerInteractionTest {
    @Test fun urgentImportanceSurvivesSoundDialogAndReopening()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        var rules by mutableStateOf(ReminderSet(listOf(ReminderAlert("alert",5))))
        var options by mutableStateOf(ReminderOptions())
        setContent {MaterialTheme {Column {ReminderEditor(rules,LocalDate(2026,11,15),{"new"},settings=ReminderEditorSettings(options=options,advanced=true,user="owner",flat=true,changed={options=it})){rules=it}}}}
        onNodeWithText("Sound",useUnmergedTree=true).performClick()
        onNodeWithText("Urgent",useUnmergedTree=true).performTouchInput {click()}
        onNodeWithText("Done").performClick()
        runOnIdle {assertEquals("urgent",rules.alerts.single().importance)}
        onNodeWithText("Sound",useUnmergedTree=true).performClick()
        onNodeWithText("Urgent").assertIsSelected()
        onNodeWithText("Cancel").performClick()
    }
    @Test fun taskPriorityAppliesOnTapAndIsRetainedAfterReopening()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        val h=UiWorkspace();var opened by mutableStateOf(true)
        try {
            setContent {FieldNotesTheme {val state by h.model.state.collectAsState();if(opened)TaskScreen(h.model,state,projectRecords(state.cache).first(),{opened=false},{},{})else Text("Closed")}}
            onNodeWithTag("task-details-content").performScrollToNode(hasText("Urgent"))
            onNodeWithText("Urgent",useUnmergedTree=true).performTouchInput {click()}
            runOnIdle {assertEquals(1,h.vault.value.outbox.single().command.data.number("priority"));assertEquals(setOf("priority"),h.vault.value.outbox.single().command.data.keys)}
            onNodeWithText("Urgent").assertIsSelected()
            onNodeWithTag("task-details-content").capture("task-priority")
            runOnIdle {opened=false};waitForIdle();runOnIdle {opened=true}
            onNodeWithTag("task-details-content").performScrollToNode(hasText("Urgent"))
            onNodeWithText("Urgent").assertIsSelected()
            onNodeWithText("High",useUnmergedTree=true).performTouchInput {click()}
            runOnIdle {assertEquals(2,h.vault.value.outbox.last().command.data.number("priority"))}
        }finally{h.scope.cancel()}
    }
    @Test fun calendarScrollsPastALargeAlertAndNewEventNeverCoversTheMonth()=runSkikoComposeUiTest(size=Size(390f,720f)) {
        var view by mutableStateOf("Month");var day by mutableStateOf("2026-11-15");var creates=0
        setContent {FieldNotesTheme {Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){PlannerCalendar(emptyList(),LocalDate(2026,11,15),kotlinx.datetime.TimeZone.UTC,day,{day=it},{},{},view,{view=it},header={OutlinedCard {Text("Needs attention\n"+"Review reminder setup and permissions. ".repeat(18))}},newEvent={creates++})}}}
        onNodeWithTag("calendar-content").performScrollToNode(hasTestTag("calendar-new-event"))
        onNodeWithTag("calendar-new-event").performTouchInput {click()};runOnIdle {assertEquals(1,creates)}
        onNodeWithTag("calendar-content").capture("calendar-scrolling")
        onNodeWithTag("calendar-content").performScrollToNode(hasTestTag("calendar-day-2026-11-30"))
        onNodeWithTag("calendar-day-2026-11-30").assertIsDisplayed().performTouchInput {click()}
        runOnIdle {assertEquals("2026-11-30",day)}
        onNodeWithTag("calendar-content").capture("calendar-month-after-scroll")
    }
    @Test fun settingsOpenSeparateFacetsAndLinkStraightToReminderRepair()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        val h=UiWorkspace();var facet by mutableStateOf<SettingsFacet?>(null);var retries=0
        try {
            setContent {FieldNotesTheme {val state by h.model.state.collectAsState();WorkspaceSettings(h.model,state,DeviceActions(androidDelivery=true,warning=SCHEDULING_FAILURE,recheckDelivery={retries++}),facet,{facet=it},{},{},{},{},{})}}
            onNodeWithText("Review reminder setup").performClick()
            onNodeWithText("3 · Retry reminder scheduling").performScrollTo().performTouchInput {click()}
            onNodeWithTag("settings-section").capture("reminder-repair")
            runOnIdle {assertEquals(1,retries);assertEquals(SettingsFacet.REMINDERS,facet)}
            onNodeWithText("‹ Settings").performClick()
            onNodeWithTag("settings-categories").performScrollToNode(hasText("Sound & alarms ›"))
            onNodeWithText("Sound & alarms ›").performClick()
            onNodeWithText("Allow temporary alarm-volume control").assertIsDisplayed()
            onNodeWithTag("settings-section").capture("sound-settings")
            onNodeWithText("Automatically prepare app updates").assertDoesNotExist()
        }finally{h.scope.cancel()}
    }
    @Test fun undatedReminderEditorOffersIntervalsWithoutRelativeDateControls()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        var rules by mutableStateOf(ReminderSet())
        setContent {FieldNotesTheme {Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){ReminderEditor(rules,LocalDate(2026,11,15),{"cycle"},settings=ReminderEditorSettings(flat=true,intervalTiming=true,anchored=false)){rules=it}}}}
        onNodeWithText("Add reminder").assertDoesNotExist()
        onNodeWithContentDescription("Repeat every interval").performTouchInput {click()}
        onNodeWithText("Repeat interval").performTextReplacement("3")
        onNodeWithText("Days",useUnmergedTree=true).performClick()
        runOnIdle {assertEquals(3,rules.interval!!.amount);assertEquals("days",rules.interval!!.unit);assertEquals(3,rules.timing_version)}
    }
    @Test fun dayTimelineCanJumpToMorningAndStillReachMidnightAndEvening()=runSkikoComposeUiTest(size=Size(390f,720f)) {
        var created=""
        setContent {FieldNotesTheme {Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){PlannerCalendar(emptyList(),LocalDate(2026,11,15),kotlinx.datetime.TimeZone.UTC,"2026-11-14",{},{created=it},{},"Day",{})}}}
        onNodeWithTag("calendar-content").performScrollToNode(hasText("Jump to morning"))
        onNodeWithText("Jump to morning").performTouchInput {click()}
        onNodeWithTag("calendar-time-420").assertIsDisplayed().performTouchInput {click()}
        runOnIdle {assertEquals("07:00",created)}
        onNodeWithTag("calendar-content").performScrollToNode(hasTestTag("calendar-time-1380"))
        onNodeWithTag("calendar-time-1380").performTouchInput {click()};runOnIdle {assertEquals("23:00",created)}
        onNodeWithTag("calendar-content").performScrollToNode(hasTestTag("calendar-time-0"))
        onNodeWithTag("calendar-time-0").performTouchInput {click()};runOnIdle {assertEquals("00:00",created)}
    }
    @Test fun listItemPriorityQueuesOnlyPriorityAndKeepsSeparateUnfinishedNotes()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        val h=UiWorkspace();val list=Record("list","list",1,obj("title" to "Work".j(),"template" to "tasks".j(),"items_are_tasks" to JsonPrimitive(true)))
        try {
            setContent {FieldNotesTheme {val state by h.model.state.collectAsState();ListItemDialog(h.model,state,list,projectRecords(state.cache).first(),DeviceActions(),close={})}}
            onNodeWithText("Notes").performScrollTo().performTextInput("Still writing these notes")
            onNodeWithText("Urgent").performScrollTo().performTouchInput{click()}
            runOnIdle {
                assertEquals(obj("priority" to 1.j()),h.vault.value.outbox.single().command.data)
                assertEquals("Still writing these notes",h.vault.value.drafts.getValue("task").data.text("notes"))
                assertEquals(1,h.vault.value.drafts.getValue("task").data.number("priority"))
            }
            onNodeWithText("Urgent").assertIsSelected()
        }finally{h.scope.cancel()}
    }

    @Test fun reminderTimingOffersBeforeAndAfterAndKeepsThePrepName()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        var result:ReminderAlert?=null
        setContent {FieldNotesTheme {ReminderTimeDialog(ReminderAlert("prep",0),true,{},dayTiming=true,afterTiming=true){result=it}}}
        onNodeWithText("Day & time").assertDoesNotExist()
        onNodeWithText("After start").performClick()
        onNodeWithText("Amount after").performTextReplacement("45")
        onNodeWithText("Reminder label · optional").performTextInput("Pack leftovers")
        onAllNodes(isRoot()).onLast().capture("after-start-reminder")
        onNodeWithText("Save time").performClick()
        runOnIdle{assertEquals(45,result!!.after_start!!.amount);assertEquals("Pack leftovers",result!!.label);assertNull(result!!.day_time)}
    }
    @Test fun mealVisibilityChangesTheCalendarWithoutDeletingTheMeal()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        var show by mutableStateOf(false)
        val meal=Record("dinner","event",1,obj("title" to "Dinner to hide".j(),"meal_kind" to "dinner".j(),"starts_at" to 1794765600L.j()))
        setContent {FieldNotesTheme {Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){PlannerCalendar(listOf(VisibleRecord(meal,false)),LocalDate(2026,11,15),kotlinx.datetime.TimeZone.UTC,"2026-11-15",{},{},{},"Month",{},showMeals=show,mealChange={show=it})}}}
        onNodeWithText("Dinner to hide",substring=true).assertDoesNotExist()
        onNodeWithTag("calendar-content").performScrollToNode(hasContentDescription("Show meals in calendar"))
        onNodeWithContentDescription("Show meals in calendar").performTouchInput{click()}
        onNodeWithTag("calendar-content").performScrollToNode(hasText("Dinner to hide",substring=true))
        onNodeWithText("Dinner to hide",substring=true).assertIsDisplayed()
        onNodeWithTag("calendar-content").capture("optional-meals-calendar")
        runOnIdle{assertEquals("dinner",meal.data.text("meal_kind"))}
    }
    @Test fun cloningMultipleDatesQueuesIndependentCopiesAndKeepsTheOriginal()=runSkikoComposeUiTest(size=Size(390f,760f)) {
        val h=UiWorkspace();val payload=obj("title" to "Dinner".j(),"starts_at" to 1794765600L.j(),"ends_at" to 0.j(),"all_day" to JsonPrimitive(false),"recurrence" to repeatData(EventRepeat("daily",zone="UTC")))
        try {
            setContent {FieldNotesTheme {CloneEventDialog(h.model,payload,h.vault.value.snapshot!!.basis,kotlinx.datetime.TimeZone.UTC,{},{})}}
            onNodeWithText("Add date").performScrollTo().performClick()
            onAllNodes(isRoot()).onLast().capture("clone-dates-and-times")
            onNodeWithText("Create 2 copies").performClick()
            runOnIdle{assertEquals(2,h.vault.value.outbox.size);assertTrue(h.vault.value.outbox.all{it.command.action=="event.create"&&it.command.data["recurrence"]==JsonNull});assertEquals(1,h.vault.value.snapshot!!.records.size)}
        }finally{h.scope.cancel()}
    }

}

private fun SemanticsNodeInteraction.capture(name:String) {
    if(System.getenv("FIELD_NOTES_UI_CAPTURE")!="synthetic-only")return
    val file=File("build/ui-captures/$name.png");file.parentFile.mkdirs()
    file.writeBytes(checkNotNull(Image.makeFromBitmap(captureToImage().asSkiaBitmap()).encodeToData()).bytes)
}

private class UiVault(var value:Cache):Vault {
    override suspend fun load()=value
    override suspend fun save(cache:Cache){value=WireJson.decodeFromString(WireJson.encodeToString(Cache.serializer(),cache))}
    override suspend fun clearImages(){}
}
private class UiWorkspace {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val vault=UiVault(Cache(keys=obj("private_key" to "synthetic".j()),token="token",snapshot=Snapshot(1,"epoch",1,1,listOf(Record("task","task",1,obj("title" to "Task priority".j(),"status" to "todo".j(),"priority" to 3.j()))),Actor("owner","Owner","owner","session"))))
    private val transport=object:Transport {
        override suspend fun keys()=error("Existing identity must be retained")
        override suspend fun configure(configuration:JsonObject){}
        override suspend fun stop(){}
        override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply=error("Offline test")
    }
    private var sequence=0
    val model=Workspace(transport,vault,{"operation-${sequence++}"},scope)
    init {runBlocking {model.initialize()}}
}
