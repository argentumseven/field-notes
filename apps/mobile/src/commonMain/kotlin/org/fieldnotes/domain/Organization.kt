package org.fieldnotes.domain

import kotlinx.datetime.LocalDate

fun workspaceContainers(cache: Cache) = projectRecords(cache).filter { it.record.kind in setOf("list", "project") }.sortedBy { it.record.data.text("title").lowercase() }
fun workspaceTasks(cache: Cache, view: String, container: String?, query: String, today: LocalDate, now: Long): List<VisibleRecord> {
    val actor=cache.snapshot?.actor?.user_id ?: return emptyList()
    val source=if(container==null)plannerTasks(cache) else projectRecords(cache).filter { it.record.kind=="task" }
    val graph=cache.projectGraph()
    val containerRecord=graph.byId[container]
    val tasks=if(view=="Focus") focusTasks(cache,today,now).map { it.first } else source.sortedWith(compareBy<VisibleRecord> { it.record.data.text("status")=="done" }.thenBy { it.record.data.number("priority",3) }.thenBy { it.record.data.number("created_at") }.thenBy { it.record.id })
    return tasks.filter { row -> val d=row.record.data
        (container==null||if(containerRecord?.kind=="project")graph.members(container).any {it.id==row.record.id} else d.text("project_id")==container) && (d.text("title")+" "+d.text("notes")).contains(query,true) && when(view) {
            "My tasks" -> d.text("status")!="done" && (d.text("assignee")==actor||d.text("claimant")==actor)
            "Unassigned" -> d.text("status")!="done" && d.text("assignee").isBlank()
            "Inbox" -> d.text("project_id").isBlank() && d.text("status")!="done"
            "Waiting" -> d.text("status")!="done" && (row.record.flag("blocked")||graph.held(row.record)||(d.text("claimant").isNotBlank()&&d.text("claimant")!=actor))
            "Completed" -> d.text("status")=="done"
            "Unscheduled" -> d.text("status")!="done" && d.text("due").isBlank()&&d.text("planned_for").isBlank()
            else -> true
        }
    }
}

fun personName(cache:Cache,id:String):String = cache.people.firstOrNull { it.id==id }?.name ?: cache.snapshot?.actor?.takeIf { it.user_id==id }?.name ?: "Unavailable member"
