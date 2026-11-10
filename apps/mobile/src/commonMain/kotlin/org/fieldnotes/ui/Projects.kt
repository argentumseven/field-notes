@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import org.fieldnotes.domain.*

internal data class ProjectView(val query:String="",val group:String="",val show:String="open",val context:Boolean=false,val groupsOpen:Boolean=false,val filters:TaskFilters=TaskFilters(sort="smart"))

@Composable internal fun ColumnScope.ProjectWorkspace(model:Workspace,state:WorkspaceState,project:Record,open:(String)->Unit,openList:(String)->Unit,edit:()->Unit,newList:(Boolean)->Unit,history:()->Unit,newTask:(String)->Unit,initialView:ProjectView=ProjectView(),saveView:(ProjectView)->Unit={},groupChanged:(String)->Unit={},header:@Composable ()->Unit={}) {
    var addExisting by remember(project.id) {mutableStateOf(false)}
    if(addExisting)AddExistingTaskDialog(model,state,project){addExisting=false}
    val graph=remember(state.cache){state.cache.projectGraph()};val today=Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString();val stats=graph.rollup(project,today)
    val writable=state.cache.snapshot?.actor?.role!="viewer";val archived=project.data.text("status")=="archived"
    var query by rememberSaveable(project.id){mutableStateOf(initialView.query)};var group by rememberSaveable(project.id){mutableStateOf(initialView.group)};var show by rememberSaveable(project.id){mutableStateOf(initialView.show)}
    var context by rememberSaveable(project.id){mutableStateOf(initialView.context)};var groupsOpen by rememberSaveable(project.id){mutableStateOf(initialView.groupsOpen)}
    var category by rememberSaveable(project.id){mutableStateOf(initialView.filters.category)};var person by rememberSaveable(project.id){mutableStateOf(initialView.filters.person)};var priority by rememberSaveable(project.id){mutableStateOf(initialView.filters.priority)};var sort by rememberSaveable(project.id){mutableStateOf(initialView.filters.sort)}
    val filters=TaskFilters(category=category,person=person,priority=priority,sort=sort)
    DisposableEffect(project.id){onDispose{saveView(ProjectView(query,group,show,context,groupsOpen,TaskFilters(category=category,person=person,priority=priority,sort=sort)))}}
    LaunchedEffect(project.id,group){groupChanged(group.takeIf{it.isNotBlank()&&it!="__direct"} ?: project.id)}
    val groups=graph.groups(project.id);val work=groups.filter {it.flag("items_are_tasks")}
    LaunchedEffect(work.map{it.id}){if(group.isNotBlank()&&group!="__direct"&&work.none{it.id==group})group=""}
    val memberIds=graph.members(project.id).map{it.id}.toSet()
    val visible=filterTasks(projectRecords(state.cache).filter {row->val task=row.record;task.id in memberIds&&(group.isBlank()||task.data.text("project_id")==if(group=="__direct")project.id else group)&&(task.data.text("title")+" "+task.data.text("notes")).contains(query,true)&&when(show){"ready"->task.data.text("status")=="todo"&&!task.flag("blocked")&&!graph.held(task)&&(task.data.text("planned_for").isBlank()||task.data.text("planned_for")<=today);"waiting"->task.data.text("status")=="todo"&&(task.flag("blocked")||graph.held(task));"done"->task.data.text("status")=="done";"all"->true;else->task.data.text("status")!="done"} }.sortedWith(compareBy<VisibleRecord>{it.record.data.text("status")=="done"}.thenBy {it.record.data.number("priority",3)}.thenBy {it.record.data.text("due").ifBlank{"9999-12-31"}}.thenBy{it.record.id}),filters)
    val queued=projectRecords(state.cache).count {it.pending&&(it.record.id==project.id||graph.project(it.record)?.id==project.id)}
    VisibleLazyColumn(Modifier.weight(1f).testTag("project-workspace"),contentPadding=PaddingValues(top=8.dp,bottom=110.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item("workspace-header"){Column {header()}}
        item {OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            RecordBadges(project);Text(projectStateLabel(project,stats),style=MaterialTheme.typography.titleMedium)
            Text(if(stats.total==0)"No work tasks yet" else "${stats.done} of ${stats.total} tasks complete",style=MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(progress={if(stats.total>0)stats.done.toFloat()/stats.total else 0f},modifier=Modifier.fillMaxWidth())
            Text("${stats.ready} ready · ${stats.working} in progress · ${stats.waiting} waiting · ${stats.later} planned later · ${stats.overdue} overdue",style=MaterialTheme.typography.bodyMedium)
            Text((if(project.data.text("assignee").isNotBlank())"Coordinator: "+personName(state.cache,project.data.text("assignee")) else "No coordinator")+" · "+(project.data.text("due").takeIf{it.isNotBlank()}?.let{"Target: "+friendlyDate(LocalDate.parse(it))} ?: "No target date"),style=MaterialTheme.typography.bodySmall)
            if(stats.open>0)Text("${stats.unassigned} unassigned · ${stats.undated} without a date · ${stats.remaining_minutes} known minutes remaining${if(stats.unestimated>0)" · ${stats.unestimated} without estimates" else ""}",style=MaterialTheme.typography.bodySmall)
            if(stats.after_target>0)Text("${stats.after_target} tasks have due dates after the project target.",color=MaterialTheme.colorScheme.error)
            if(queued>0)Text("Includes $queued queued changes. Final progress follows the server.",style=MaterialTheme.typography.labelMedium)
            if(project.data.text("notes").isNotBlank()){SecondaryButton(onClick={context=!context}){Text(if(context)"Hide goal & context" else "Goal & context")};if(context)Text(project.data.text("notes"))}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(writable){SecondaryButton(onClick=edit){Text("Project settings")};if(project.data.text("status")!="active"&&project.data.text("status").isNotBlank())SecondaryButton(onClick={model.enqueueAsync("project.update",project.id,obj("status" to "active".j()),project.revision)}){Text("Reopen project")}else SecondaryButton(onClick={model.enqueueAsync("project.update",project.id,obj("status" to "on_hold".j()),project.revision)},enabled=stats.working==0){Text("Put on hold")};if(!archived)SecondaryButton(onClick={model.enqueueAsync("project.update",project.id,obj("status" to "archived".j()),project.revision)},enabled=stats.open==0){Text("Finish project")}}
                SecondaryButton(onClick=history){Text("Project history")}
            }
        }}}
        item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query,{query=it},label={Text("Search project tasks and notes")},singleLine=true,modifier=Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                ViewChoice("Work group",group,listOf("" to "All project work","__direct" to "Direct tasks")+work.map{it.id to it.data.text("title")}){group=it}
                ViewChoice("Show",show,listOf("open" to "Open tasks","ready" to "Ready","waiting" to "Waiting","done" to "Completed","all" to "All tasks")){show=it}
            }
            TaskFilterControls(state.cache,filters,includeStatus=false){category=it.category;person=it.person;priority=it.priority;sort=it.sort}
            if(writable&&!archived)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(shape=ControlShape,onClick={newTask(group.takeIf{it.isNotBlank()&&it!="__direct"} ?: project.id)}){Text("New task")}
                SecondaryButton(onClick={addExisting=true}){Text("Add existing task")}
                SecondaryButton(onClick={newList(true)}){Text("New task list")};SecondaryButton(onClick={newList(false)}){Text("New reference list")}
            }
            if(groups.isNotEmpty()) {
                SecondaryButton(onClick={groupsOpen=!groupsOpen}){Text("Lists in this project · ${work.size} task lists · ${groups.size-work.size} reference lists")}
                if(groupsOpen){for(list in groups)SecondaryButton(onClick={openList(list.id)},modifier=Modifier.fillMaxWidth()){Text(list.data.text("title")+" · "+if(list.flag("items_are_tasks"))"Task list" else "Reference list")};Text("Task-list items count toward project progress. Shopping and reference checklists stay separate.",style=MaterialTheme.typography.bodySmall)}
            }
        }}
        if(visible.isEmpty())item{Text(if(archived)"Completed work is kept. Choose All tasks to view it, or reopen this project to add work." else "No tasks match this view. Progress above always includes every work group.",Modifier.padding(vertical=20.dp))}
        items(visible,key={it.record.id}){row->TaskCard(row,state.cache){open(row.record.id)}}
    }
}
