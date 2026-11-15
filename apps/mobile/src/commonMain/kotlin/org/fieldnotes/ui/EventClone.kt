@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun CloneEventDialog(model:Workspace,payload:JsonObject,basis:Basis,zone:TimeZone,close:()->Unit,done:()->Unit,use24Hour:Boolean=false) {
    val source=if(payload["all_day"]==JsonPrimitive(true))LocalDate.parse(payload.text("start_date"))else Instant.fromEpochSeconds(payload.number("starts_at")).toLocalDateTime(zone).date
    var dates by remember{mutableStateOf(listOf(source.plus(1,DateTimeUnit.DAY).toString()))}
    val all=payload["all_day"]==JsonPrimitive(true)
    val originalTime=if(all)"18:00"else Instant.fromEpochSeconds(payload.number("starts_at")).toLocalDateTime(zone).time.toString().take(5)
    var times by remember{mutableStateOf(listOf(originalTime))}
    var busy by remember{mutableStateOf(false)};var problem by remember{mutableStateOf<String?>(null)}
    EditorDialog(onDismissRequest={if(!busy)close()},title={Text("Clone event")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("${payload.text("title")} · choose dates and times, with the same details. Each copy is independent; the original and its repeating series stay unchanged.")
        if(!all)Text("Times shown in · ${zone.id.replace('_',' ')}",style=MaterialTheme.typography.bodySmall)
        dates.forEachIndexed{i,date->Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Column(Modifier.weight(1f)){DateField("Copy ${i+1}",date,!busy,clearable=false){next->dates=dates.mapIndexed{n,d->if(n==i)next else d}};if(!all)TimeField("Copy ${i+1} time",times[i],use24Hour,!busy){next->times=times.mapIndexed{n,t->if(n==i)next else t}}};if(dates.size>1)TextButton(onClick={dates=dates.filterIndexed{n,_->n!=i};times=times.filterIndexed{n,_->n!=i}},enabled=!busy){Text("Remove")}}}
        SecondaryButton(onClick={dates=dates+LocalDate.parse(dates.last()).plus(1,DateTimeUnit.DAY).toString();times=times+times.last()},enabled=!busy&&dates.size<31){Text("Add date")}
        problem?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button(shape=ControlShape,enabled=!busy,onClick={busy=true;model.launch{try{require(dates.mapIndexed{i,d->d+if(all)""else times[i]}.distinct().size==dates.size){"Choose each date and time once."};model.enqueueEventCopies(dates.mapIndexed{i,d->eventCopyForDate(payload,LocalDate.parse(d),zone,if(all)null else LocalTime.parse(times[i]))},basis);model.requestSync();done()}catch(e:Exception){problem=e.message}finally{busy=false}}}){Text("Create ${dates.size} "+if(dates.size==1)"copy"else"copies")}},dismissButton={SecondaryButton(onClick=close,enabled=!busy){Text("Cancel")}})
}
