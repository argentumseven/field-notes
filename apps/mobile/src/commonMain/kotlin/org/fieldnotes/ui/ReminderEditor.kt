@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import kotlinx.coroutines.launch
import org.fieldnotes.domain.*

@Composable internal fun ReminderEditor(value:ReminderSet,day:LocalDate,newId:()->String,use24Hour:Boolean=false,preview:(AlertProfile)->Unit={},changed:(ReminderSet)->Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var soundFor by remember { mutableStateOf<String?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) { Text("Reminders · ${value.alerts.size}"+(if(value.repeat!=null)" + recurring" else "")+if(expanded)" ▴" else " ▾") }
        if(expanded) {
            Text("Multiple alerts can use different sounds and importance. Phone volume, DND and profile settings stay in control.",style=MaterialTheme.typography.bodySmall)
            value.alerts.forEach { rule -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) { Text(if(rule.minutes_before==0)"At the start" else "${rule.minutes_before} min before");Text("${rule.importance} · ${rule.sound} · ${rule.volume}%",style=MaterialTheme.typography.labelSmall) }
                TextButton(onClick={soundFor=rule.id}){Text("Sound")}
                TextButton(onClick={changed(value.copy(alerts=value.alerts.filterNot { it.id==rule.id }))}){Text("Remove")}
            } }
            Box { SecondaryButton(onClick={addMenu=true},enabled=value.alerts.size<8){Text("Add reminder")};DropdownMenu(addMenu,{addMenu=false}) {
                for(minutes in listOf(0,5,10,15,30,60,120,1440))DropdownMenuItem(text={Text(if(minutes==0)"At the start" else if(minutes==1440)"1 day before" else "$minutes minutes before")},onClick={changed(value.copy(alerts=value.alerts+ReminderAlert(newId(),minutes)));addMenu=false})
            } }
            Row(verticalAlignment=Alignment.CenterVertically) { Switch(value.repeat!=null,{checked -> changed(value.copy(repeat=if(checked) ReminderWindow(newId(),starts_on=day.toString(),zone=TimeZone.currentSystemDefault().id) else null)) });Text("Repeat during a time window",Modifier.padding(start=8.dp)) }
            value.repeat?.let { rule ->
                Text("These are reminders, not blocks of occupied calendar time.",style=MaterialTheme.typography.bodySmall)
                DateField("First reminder day",rule.starts_on,clearable=false){changed(value.copy(repeat=rule.copy(starts_on=it)))}
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(rule.weekdays.isEmpty(),{changed(value.copy(repeat=rule.copy(weekdays=emptyList())))},{Text("Once")})
                    FilterChip(rule.weekdays.isNotEmpty(),{changed(value.copy(repeat=rule.copy(weekdays=listOf(LocalDate.parse(rule.starts_on).dayOfWeek.isoDayNumber))))},{Text("Every week")})
                }
                if(rule.weekdays.isNotEmpty()) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { for(d in 1..7)FilterChip(d in rule.weekdays,{ val days=if(d in rule.weekdays)rule.weekdays-d else rule.weekdays+d;changed(value.copy(repeat=rule.copy(weekdays=days.ifEmpty { listOf(d) }.sorted()))) },{Text(DayOfWeek(d).name.take(3).lowercase().replaceFirstChar { it.uppercase() })}) }
                    TextButton(onClick={changed(value.copy(repeat=rule.copy(weekdays=(1..7).toList())))}){Text("Every day")}
                    DateField("Last reminder day · optional",rule.ends_on){changed(value.copy(repeat=rule.copy(ends_on=it)))}
                }
                TimeField("From",rule.from,use24Hour){changed(value.copy(repeat=rule.copy(from=it)))}
                TimeField("Until",rule.until,use24Hour){changed(value.copy(repeat=rule.copy(until=it)))}
                var hours by rememberSaveable(rule.id){mutableStateOf(rule.every_minutes>=60&&rule.every_minutes%60==0)}
                var interval by rememberSaveable(rule.id){mutableStateOf((if(hours)rule.every_minutes/60 else rule.every_minutes).toString())}
                OutlinedTextField(interval,{interval=it.take(4);changed(value.copy(repeat=rule.copy(every_minutes=(it.toIntOrNull() ?: 0)*(if(hours)60 else 1))))},label={Text("Repeat every")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { for(h in listOf(false,true))FilterChip(hours==h,{
                    hours=h;val next=if(h)(rule.every_minutes/60).coerceAtLeast(1) else rule.every_minutes.coerceAtLeast(1);interval=next.toString();changed(value.copy(repeat=rule.copy(every_minutes=next*(if(h)60 else 1))))
                },{Text(if(h)"Hours" else "Minutes")}) }
                Text("${rule.zone.replace('_',' ')} · windows can continue past midnight",style=MaterialTheme.typography.labelSmall)
                SecondaryButton(onClick={soundFor=rule.id}){Text("Recurring reminder sound & importance")}
            }
            if(value.alerts.isEmpty()&&value.repeat==null)Text("No reminders for this item.",style=MaterialTheme.typography.bodySmall)
        }
    } }
    soundFor?.let { id ->
        val alert=value.alerts.find { it.id==id };val rule=value.repeat?.takeIf { it.id==id };val profile=alert?.profile ?: rule?.profile
        if(profile!=null) SoundProfileDialog(profile,preview,{soundFor=null}) { p ->
            changed(if(alert!=null)value.copy(alerts=value.alerts.map { if(it.id==id)it.copy(importance=p.importance,sound=p.sound,volume=p.volume,vibrate=p.vibrate) else it }) else value.copy(repeat=rule!!.copy(importance=p.importance,sound=p.sound,volume=p.volume,vibrate=p.vibrate)))
            soundFor=null
        }
    }
}
@Composable private fun SoundProfileDialog(initial:AlertProfile,preview:(AlertProfile)->Unit,close:()->Unit,save:(AlertProfile)->Unit) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=close,title={Text("Sound & importance")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Importance")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(level in listOf("quiet","normal","urgent"))FilterChip(p.importance==level,{p=p.copy(importance=level)},{Text(level.replaceFirstChar { it.uppercase() })})}
        if(p.importance!="quiet") {
            Text("Sound")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(tone in listOf("chime","bell","pulse"))FilterChip(p.sound==tone,{p=p.copy(sound=tone)},{Text(tone.replaceFirstChar { it.uppercase() })})}
            Text("Sound level · relative to phone volume")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(level in listOf(25,50,75,100))FilterChip(p.volume==level,{p=p.copy(volume=level)},{Text("$level%")})}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(p.vibrate,{p=p.copy(vibrate=it)});Text("Vibration",Modifier.padding(start=8.dp))}
            SecondaryButton(onClick={preview(p)}){Text("Preview sound")}
        }
        Text("Urgent can show a heads-up alert. Android can mute or change each channel; this never overrides DND or the phone's volume.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={Button(onClick={save(p)}){Text("Done")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}

@Composable internal fun TaskReminderDialog(model:Workspace,state:WorkspaceState,record:Record,device:DeviceActions,close:()->Unit) {
    val initial=reminderSet(record) ?: ReminderSet(if(record.data.text("due").isNotBlank())listOf(ReminderAlert(record.id,0)) else emptyList())
    var json by rememberSaveable(record.id){mutableStateOf(reminderData(initial).toString())};var error by remember{mutableStateOf<String?>(null)}
    val rules=WireJson.decodeFromString<ReminderSet>(json);val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};val basis=remember{state.cache.snapshot!!.basis}
    val day=runCatching{LocalDate.parse(record.data.text("due").ifBlank{record.data.text("planned_for")})}.getOrDefault(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
    AlertDialog(onDismissRequest=close,title={Text("Reminders")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(record.data.text("title"));Text("Date reminders use 9 AM on the due or planned day. A recurring window works even without a due date.",style=MaterialTheme.typography.bodySmall)
        ReminderEditor(rules,day,model::newId,device.use24Hour,device.previewSound){json=reminderData(it).toString()}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button(enabled=!busy,onClick={busy=true;scope.launch{try{validateReminders(rules);model.enqueue("task.update",record.id,obj("reminders" to reminderData(rules)),record.revision,basis);close();model.sync()}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false}}}){Text("Save reminders")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
