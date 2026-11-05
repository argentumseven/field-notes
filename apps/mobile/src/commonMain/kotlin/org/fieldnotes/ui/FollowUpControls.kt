@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import org.fieldnotes.domain.*

@Composable internal fun FollowUpControls(onRadar:Boolean,at:Long,use24Hour:Boolean,enabled:Boolean=true,changed:(Boolean,Long)->Unit) {
    val zone=TimeZone.currentSystemDefault();val now=Clock.System.now();val local=Instant.fromEpochSeconds(if(at>0)at else now.epochSeconds+3600).toLocalDateTime(zone)
    var problem by remember { mutableStateOf<String?>(null) }
    fun set(date:String,time:String){try{changed(true,scheduledInstant(date,time,zone,at));problem=null}catch(e:Exception){problem=e.message}}
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Switch(onRadar,{changed(it,if(it)at else 0)},enabled=enabled);Text("Keep in Later & reminders",Modifier.padding(start=8.dp))}
        if(onRadar) {
            Text("An idea can stay undated. A reminder brings it back without blocking calendar time.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically){Switch(at>0,{changed(true,if(it)scheduledInstant(local.date.toString(),local.time.toString().take(5),zone) else 0)},enabled=enabled);Text("Remind me",Modifier.padding(start=8.dp))}
            if(at>0){DateField("Remind on",local.date.toString(),enabled,clearable=false){set(it,local.time.toString().take(5))};TimeField("At",local.time.toString().take(5),use24Hour,enabled){set(local.date.toString(),it)}}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                for((days,title) in listOf(1 to "Tomorrow",7 to "In a week"))SecondaryButton(onClick={set(now.toLocalDateTime(zone).date.plus(days,DateTimeUnit.DAY).toString(),if(at>0)local.time.toString().take(5) else "09:00")},enabled=enabled){Text(title)}
                SecondaryButton(onClick={changed(true,0)},enabled=enabled){Text("Someday · no reminder")}
            }
            Text(if(at>0)"Reminder time is local · ${zone.id.replace('_',' ')}" else "Undated idea · no alarm scheduled",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        problem?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    } }
}
