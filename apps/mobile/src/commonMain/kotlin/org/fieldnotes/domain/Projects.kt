package org.fieldnotes.domain

import kotlinx.serialization.json.*

const val PROJECTS_FEATURE="project-workspaces-v1"
const val COLLECTION_ACTIONS_FEATURE="collection-lifecycle-v2"
fun Cache.supportsCollectionActions()=compatibility?.let{!it.blocked&&COLLECTION_ACTIONS_FEATURE in it.features}==true
fun Cache.supportsProjects()=compatibility?.let {!it.blocked&&PROJECTS_FEATURE in it.features}==true
class ProjectGraph(val rows:List<Record>) {
    val byId=rows.associateBy {it.id}
    val lists=rows.filter {it.kind=="list"}
    val tasks=rows.filter {it.kind=="task"}
    private val work=tasks.mapNotNull {task->workProject(task)?.let {it.id to task}}.groupBy({it.first},{it.second})
    fun project(record:Record?):Record? {
        if(record==null)return null
        if(record.kind=="project")return record
        val parent=byId[record.data.text("project_id").ifBlank {record.data.text("task_id")}] ?: return null
        return if(parent.kind=="project")parent else if(parent.kind in setOf("task","list"))project(parent) else null
    }
    fun workProject(task:Record):Record? {val parent=byId[task.data.text("project_id")];return if(parent?.kind=="list"&&!parent.flag("items_are_tasks"))null else project(task)}
    fun held(task:Record)=workProject(task)?.data?.text("status","active")?.let {it!="active"} ?: false
    fun planner(task:Record)=task.kind=="task"&&byId[task.data.text("project_id")]?.let{it.kind!="list"||it.flag("items_are_tasks")}!=false
    fun path(id:String):String {val row=byId[id] ?: return if(id.isBlank())"Inbox" else "Collection unavailable";val project=if(row.kind=="list")project(row) else null;return (project?.let{it.data.text("title")+" › "} ?: "")+row.data.text("title")}
    fun members(id:String)=work[id].orEmpty()
    fun groups(id:String)=lists.filter {it.data.text("project_id")==id}
    fun rollup(project:Record,today:String):ProjectRollup {
        val all=members(project.id);val open=all.filter {it.data.text("status")!="done"}
        val ready=open.count {it.data.text("status")=="todo"&&!it.flag("blocked")&&!held(it)&&(it.data.text("planned_for").isBlank()||it.data.text("planned_for")<=today)}
        return ProjectRollup(all.size,all.size-open.size,open.size,open.count {it.data.text("status")=="in_progress"},open.count {it.data.text("status")=="todo"&&(it.flag("blocked")||held(it))},ready,
            open.count {it.data.text("status")=="todo"&&!it.flag("blocked")&&!held(it)&&it.data.text("planned_for")>today},open.count {it.data.text("due").isNotBlank()&&it.data.text("due")<today},open.count {it.data.text("assignee").isBlank()},open.count {it.data.text("due").isBlank()&&it.data.text("planned_for").isBlank()},open.sumOf {it.data.number("estimate_minutes")},open.count {it.data.number("estimate_minutes")==0L},open.count {project.data.text("due").isNotBlank()&&it.data.text("due")>project.data.text("due")},(tasks.filter{this.project(it)?.id==project.id}+project+groups(project.id)).maxOfOrNull {it.data.number("last_activity_at",it.data.number("updated_at"))} ?: 0)
    }
}
@kotlinx.serialization.Serializable data class ProjectRollup(val total:Int,val done:Int,val open:Int,val working:Int,val waiting:Int,val ready:Int,val later:Int,val overdue:Int,val unassigned:Int,val undated:Int,val remaining_minutes:Long,val unestimated:Int,val after_target:Int,val activity:Long)
fun projectStateLabel(project:Record,stats:ProjectRollup)=when{project.data.text("status")=="archived"->"Finished";project.data.text("status")=="on_hold"->"On hold";stats.total==0->"Planning";stats.open==0->"Ready to finish";stats.working>0->"In progress";stats.ready>0->"Ready";stats.waiting>0->"Waiting";else->"Scheduled"}
fun Cache.projectGraph()=ProjectGraph(projectRecords(this).map {it.record})
