package org.fieldnotes.domain

data class TaskFilters(val category:String="",val person:String="*",val status:String="all",val priority:Int=0,val sort:String="priority")
fun filterTasks(rows:List<VisibleRecord>,filters:TaskFilters):List<VisibleRecord> {
    val selected=rows.filter { row->val d=row.record.data
        (filters.category.isBlank()||categoryKey(d.text("category","general"))==categoryKey(filters.category))&&
            (filters.person=="*"||d.text("assignee")==filters.person)&&
            (filters.status=="all"||if(filters.status=="open")d.text("status")!="done" else d.text("status")==filters.status)&&
            (filters.priority==0||d.number("priority",3).toInt()==filters.priority)
    }
    return when(filters.sort){
        "smart"->selected
        "title"->selected.sortedWith(compareBy<VisibleRecord>{it.record.data.text("title").lowercase()}.thenBy{it.record.id})
        "updated"->selected.sortedWith(compareByDescending<VisibleRecord>{it.record.data.number("last_activity_at",it.record.data.number("updated_at"))}.thenBy{it.record.id})
        "due","planned_for"->selected.sortedWith(compareBy<VisibleRecord>{it.record.data.text(filters.sort).ifBlank { "9999-12-31" }}.thenBy{it.record.data.number("priority",3)}.thenBy{it.record.id})
        "estimate_minutes"->selected.sortedWith(compareBy<VisibleRecord>{it.record.data.number("estimate_minutes").takeIf{n->n>0} ?: Long.MAX_VALUE}.thenBy{it.record.id})
        else->selected
    }
}
