package org.fieldnotes.domain
import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
class OrganizationTest {
    @Test fun inboxListsProjectsNotesAndSmartViewsHaveDistinctMeaning() {
        val records=listOf(
            Record("shopping","list",1,obj("title" to "Groceries".j())),Record("renovation","project",1,obj("title" to "Kitchen".j())),Record("reference","note",1,obj("title" to "Useful reference".j())),
            Record("inbox","task",1,obj("title" to "Capture".j(),"status" to "todo".j())),Record("milk","task",1,obj("title" to "Milk".j(),"project_id" to "shopping".j(),"status" to "todo".j())),
            Record("waiting","task",1,obj("title" to "Wait".j(),"status" to "todo".j(),"blocked" to JsonPrimitive(true))),Record("done","task",1,obj("title" to "Finished".j(),"status" to "done".j()))
        )
        val c=Cache(snapshot=Snapshot(1,"e",1,0,records,Actor("me","Owner","owner","s")))
        val day=LocalDate.parse("2026-10-30")
        assertEquals(setOf("shopping","renovation"),workspaceContainers(c).map { it.record.id }.toSet())
        assertEquals(setOf("inbox","waiting"),workspaceTasks(c,"Inbox",null,"",day,1793361600).map { it.record.id }.toSet())
        assertEquals("milk",workspaceTasks(c,"Tasks","shopping","",day,1793361600).single().record.id)
        assertEquals("waiting",workspaceTasks(c,"Waiting",null,"",day,1793361600).single().record.id)
        assertEquals("done",workspaceTasks(c,"Completed",null,"",day,1793361600).single().record.id)
        assertFalse(workspaceTasks(c,"Focus",null,"",day,1793361600).any { it.record.kind!="task"||it.record.id in setOf("waiting","done") })
    }
    @Test fun responsibilityViewsAndRemindersRespectThePersonWithoutLosingClaims() {
        val tasks=listOf(
            Record("mine","task",1,obj("title" to "Mine".j(),"status" to "todo".j(),"assignee" to "me".j(),"due" to "2026-10-30".j())),
            Record("theirs","task",1,obj("title" to "Theirs".j(),"status" to "todo".j(),"assignee" to "sam".j(),"due" to "2026-10-30".j())),
            Record("free","task",1,obj("title" to "Free".j(),"status" to "todo".j(),"due" to "2026-10-30".j())),
            Record("working","task",1,obj("title" to "Working".j(),"status" to "in_progress".j(),"claimant" to "me".j())),
            Record("done","task",1,obj("title" to "Done".j(),"status" to "done".j(),"assignee" to "me".j()))
        )
        val cache=Cache(snapshot=Snapshot(1,"e",1,0,tasks,Actor("me","Alex","owner","s")),people=listOf(HouseholdPerson("me","Alex",true),HouseholdPerson("sam","Sam",false)),preferences=Preferences(reminders=true))
        val day=LocalDate(2026,10,30)
        assertEquals(setOf("mine","working"),workspaceTasks(cache,"My tasks",null,"",day,1793361600).map { it.record.id }.toSet())
        assertEquals(setOf("free","working"),workspaceTasks(cache,"Unassigned",null,"",day,1793361600).map { it.record.id }.toSet())
        assertEquals(setOf("mine","free","working"),workspaceTasks(cache,"Focus",null,"",day,1793361600).map { it.record.id }.toSet())
        assertEquals(setOf("mine:2026-10-30","free:2026-10-30"),dueReminderKeys(cache,"2026-10-30",10))
        assertEquals("Sam",personName(cache,"sam"));assertEquals("Unavailable member",personName(cache,"missing"))
    }
}
