@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import org.fieldnotes.domain.*

@Composable internal fun TaskFilterControls(cache:Cache,value:TaskFilters,includeStatus:Boolean=true,changed:(TaskFilters)->Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val count=listOf(value.category.isNotBlank(),value.person!="*",value.status!="all",value.priority!=0).count{it}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        SecondaryButton(onClick={expanded=!expanded}){Text("Filters"+(if(count>0)" · $count active" else "")+if(expanded)" ▴" else " ▾")}
        ViewChoice("Sort",value.sort,listOf("smart" to "View order","priority" to "Priority","title" to "Name A–Z","updated" to "Recently changed","due" to "Due date","planned_for" to "Planned day","estimate_minutes" to "Effort")){changed(value.copy(sort=it))}
        if(count>0)SecondaryButton(onClick={changed(TaskFilters(sort=value.sort))}){Text("Clear filters")}
    }
    if(expanded)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        ViewChoice("Category",value.category,listOf("" to "All categories")+categoryChoices(cache,value.category)){changed(value.copy(category=it))}
        ViewChoice("Person",value.person,listOf("*" to "Everyone","" to "Unassigned")+cache.people.map { it.id to it.name }){changed(value.copy(person=it))}
        if(includeStatus)ViewChoice("Status",value.status,listOf("all" to "All statuses","open" to "Open","todo" to "To do","in_progress" to "In progress","done" to "Done")){changed(value.copy(status=it))}
        ViewChoice("Priority",value.priority.toString(),listOf("0" to "Any priority")+(1..4).map{it.toString() to priorityName(it)}){changed(value.copy(priority=it.toInt()))}
    }
}
@Composable internal fun ColumnScope.CollectionLibrary(cache:Cache,kinds:List<String>,writable:Boolean,open:(String)->Unit,edit:(Record)->Unit,header:@Composable ()->Unit={}) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var query by rememberSaveable(kinds.joinToString()){mutableStateOf("")};var template by rememberSaveable{mutableStateOf("*")};var category by rememberSaveable{mutableStateOf("")};var sort by rememberSaveable{mutableStateOf("title")};var projectState by rememberSaveable{mutableStateOf("current")}
    val records=workspaceContainers(cache).filter { val r=it.record;r.kind in kinds&&(r.kind!="project"||projectState=="all"||if(projectState=="current")r.data.text("status","active")!="archived" else r.data.text("status","active")==projectState)&&(r.data.text("title")+" "+r.data.text("notes")).contains(query,true)&&(template=="*"||r.kind!="list"||(if(r.flag("items_are_tasks"))"tasks" else r.data.text("template","general"))==template)&&(category.isBlank()||categoryKey(r.data.text("category","general"))==categoryKey(category)) }.let{if(sort=="updated")it.sortedByDescending{r->if(r.record.kind=="project")cache.projectGraph().rollup(r.record,kotlinx.datetime.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date.toString()).activity else r.record.data.number("last_activity_at",r.record.data.number("updated_at"))}else it}
    VisibleLazyColumn(Modifier.weight(1f).testTag("collection-library"),contentPadding=PaddingValues(top=8.dp,bottom=90.dp),hideKeyboardOnDrag=true) {
        item("workspace-header"){Column {header()}}
        item("collection-controls"){Column {
    OutlinedTextField(query,{query=it},label={Text("Search names and descriptions")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),modifier=Modifier.fillMaxWidth().padding(vertical=10.dp))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if("list" in kinds)ViewChoice("Type",template,listOf("*" to "All list types")+listTemplates.filterKeys{it!="tasks"||cache.supportsProjects()}.toList()){template=it}
        if("project" in kinds&&cache.supportsProjects())ViewChoice("Project state",projectState,listOf("current" to "Current projects","active" to "Active","on_hold" to "On hold","archived" to "Finished","all" to "All projects")){projectState=it}
        ViewChoice("Category",category,listOf("" to "All categories")+categoryChoices(cache,category)){category=it}
        ViewChoice("Sort",sort,listOf("title" to "Name A–Z","updated" to "Recently changed")){sort=it}
    }
        }}
        if(records.isEmpty())item{Text("Nothing matches these filters. Change a filter or create a new collection.",Modifier.padding(vertical=20.dp))}
        items(records,key={it.record.id}) {c->CollectionRow(cache,c,writable,{open(c.record.id)},{edit(c.record)})}
    }
}
