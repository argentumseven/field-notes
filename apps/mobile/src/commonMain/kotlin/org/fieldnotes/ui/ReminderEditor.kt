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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import kotlinx.coroutines.launch
import org.fieldnotes.domain.*

internal data class ReminderEditorSettings(val options:ReminderOptions=ReminderOptions(),val people:List<HouseholdPerson> = emptyList(),val user:String="",val advanced:Boolean=false,val assigned:Boolean=false,val allowLoud:Boolean=false,val enableLoud:()->Unit={},val changed:(ReminderOptions)->Unit={})
internal fun reminderEditorSettings(model:Workspace,state:WorkspaceState,options:ReminderOptions,assigned:Boolean=false,changed:(ReminderOptions)->Unit)=ReminderEditorSettings(options,state.cache.people,state.cache.snapshot!!.actor.user_id,state.cache.supportsRemindersV2(),assigned,state.cache.preferences.allowAlarmVolumeOverride,{model.launch { model.preferences(state.cache.preferences.copy(allowAlarmVolumeOverride=true)) }},changed)
@Composable internal fun ReminderEditor(value:ReminderSet,day:LocalDate,newId:()->String,use24Hour:Boolean=false,preview:(AlertProfile)->Unit={},settings:ReminderEditorSettings=ReminderEditorSettings(),changed:(ReminderSet)->Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var soundFor by remember { mutableStateOf<String?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) { Text("Reminders · ${value.alerts.size}"+(if(value.repeat!=null)" + recurring" else "")+if(expanded)" ▴" else " ▾") }
        if(expanded) {
            if(settings.advanced)ReminderAudienceEditor(settings)
            else Text("Basic reminders remain compatible with this server. Update the paired server to choose people, longer sounds and alarm mode.",style=MaterialTheme.typography.bodySmall)
            Text("Choose a notification or a longer alarm for each reminder.",style=MaterialTheme.typography.bodySmall)
            value.alerts.forEach { rule -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                val p=settings.options.playback[rule.id]?.applyTo(rule.profile) ?: rule.profile
                Column(Modifier.weight(1f)) { Text(if(rule.minutes_before==0)"At the start" else "${rule.minutes_before} min before");Text("${if(p.alarm)"Alarm" else p.importance} · ${reminderTones[p.sound] ?: p.sound} · ${if(p.duration>0)"${p.duration} s" else "once"} · ${p.volume}%",style=MaterialTheme.typography.labelSmall) }
                TextButton(onClick={soundFor=rule.id}){Text("Sound")}
                TextButton(onClick={changed(value.copy(alerts=value.alerts.filterNot { it.id==rule.id }))}){Text("Remove")}
            } }
            Box { SecondaryButton(onClick={addMenu=true},enabled=value.alerts.size<8){Text("Add reminder")};DropdownMenu(addMenu,{addMenu=false}) {
                for(minutes in listOf(0,5,10,15,30,60,120,1440))DropdownMenuItem(text={Text(if(minutes==0)"At the start" else if(minutes==1440)"1 day before" else "$minutes minutes before")},onClick={changed(value.copy(alerts=value.alerts+ReminderAlert(newId(),minutes)));addMenu=false})
            } }
            Row(verticalAlignment=Alignment.CenterVertically) { Switch(value.repeat!=null,{checked -> changed(value.copy(repeat=if(checked) ReminderWindow(newId(),starts_on=day.toString(),zone=TimeZone.currentSystemDefault().id) else null)) },modifier=Modifier.semantics {contentDescription="Repeat during a time window"});Text("Repeat during a time window",Modifier.padding(start=8.dp)) }
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
        val alert=value.alerts.find { it.id==id };val rule=value.repeat?.takeIf { it.id==id };val base=alert?.profile ?: rule?.profile
        val profile=base?.let { settings.options.playback[id]?.applyTo(it) ?: it }
        if(profile!=null) SoundProfileDialog(profile,preview,settings,{soundFor=null}) { p ->
            changed(if(alert!=null)value.copy(alerts=value.alerts.map { if(it.id==id)it.copy(importance=p.importance,sound=legacyTone(p.sound),volume=p.volume,vibrate=p.vibrate) else it }) else value.copy(repeat=rule!!.copy(importance=p.importance,sound=legacyTone(p.sound),volume=p.volume,vibrate=p.vibrate)))
            if(settings.advanced)settings.changed(prunedReminderOptions(settings.options,value).copy(playback=prunedReminderOptions(settings.options,value).playback+(id to playbackOptions(p))))
            soundFor=null
        }
    }
}
@Composable private fun ReminderAudienceEditor(settings:ReminderEditorSettings) {
    val audience=settings.options.audience
    Text("Who should receive these reminders?",style=MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterChip(audience.mode=="people"&&audience.people==listOf(settings.user),{settings.changed(settings.options.copy(audience=ReminderAudience("people",listOf(settings.user))))},{Text("Only me")})
        FilterChip(audience.mode=="everyone",{settings.changed(settings.options.copy(audience=ReminderAudience()))},{Text("Everyone")})
        if(settings.assigned)FilterChip(audience.mode=="assigned",{settings.changed(settings.options.copy(audience=ReminderAudience("assigned")))},{Text("Assigned person")})
    }
    Text("Or choose household people. This controls alerts; the calendar stays shared.",style=MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        for(person in settings.people)FilterChip(audience.mode=="people"&&person.id in audience.people,{
            val selected=if(audience.mode=="people")audience.people else emptyList()
            val ids=if(person.id in selected)selected-person.id else selected+person.id
            settings.changed(settings.options.copy(audience=ReminderAudience("people",ids)))
        },{Text(person.name)})
    }
    if(audience.mode=="people"&&audience.people.isEmpty())Text("Choose at least one person.",color=MaterialTheme.colorScheme.error)
}
@Composable private fun SoundProfileDialog(initial:AlertProfile,preview:(AlertProfile)->Unit,settings:ReminderEditorSettings,close:()->Unit,save:(AlertProfile)->Unit) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=close,title={Text("Sound & importance")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Importance")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(level in listOf("quiet","normal","urgent"))FilterChip(p.importance==level,{p=p.copy(importance=level,alarm=if(level=="quiet")false else p.alarm,unmute=if(level=="quiet")false else p.unmute)},{Text(level.replaceFirstChar { it.uppercase() })})}
        if(p.importance!="quiet") {
            Text("Sound")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for((tone,name) in reminderTones.filterKeys { settings.advanced||it in setOf("chime","bell","pulse") })FilterChip(p.sound==tone,{p=p.copy(sound=tone)},{Text(name)})}
            if(settings.advanced) {
                Row(verticalAlignment=Alignment.CenterVertically){Switch(p.alarm,{p=p.copy(alarm=it,duration=if(it&&p.duration==0)30 else p.duration,unmute=it)},modifier=Modifier.semantics {contentDescription="Play as an alarm"});Text("Play as an alarm",Modifier.padding(start=8.dp))}
                if(p.alarm) {
                    Text("Ring for")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(seconds in listOf(5,10,15,30,60))FilterChip(p.duration==seconds,{p=p.copy(duration=seconds)},{Text("$seconds s")})}
                    Row(verticalAlignment=Alignment.CenterVertically){Switch(p.unmute,{p=p.copy(unmute=it)},modifier=Modifier.semantics {contentDescription="Temporarily raise alarm volume"});Text("Temporarily raise alarm volume",Modifier.padding(start=8.dp))}
                    if(p.unmute) {
                        Text("Alarm volume · ${p.alarmVolume}%")
                        Slider(p.alarmVolume.toFloat(),{p=p.copy(alarmVolume=it.toInt())},valueRange=25f..100f,steps=2)
                        Text("Restores the previous alarm volume afterward, unless you change it yourself.",style=MaterialTheme.typography.bodySmall)
                        if(!settings.allowLoud)SecondaryButton(onClick=settings.enableLoud){Text("Enable loud alarms on this phone")}
                    }
                }
            }
            Text("Sound level · relative to phone volume")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(level in listOf(25,50,75,100))FilterChip(p.volume==level,{p=p.copy(volume=level)},{Text("$level%")})}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(p.vibrate,{p=p.copy(vibrate=it)},modifier=Modifier.semantics {contentDescription="Vibration"});Text("Vibration",Modifier.padding(start=8.dp))}
            SecondaryButton(onClick={preview(p)}){Text("Preview sound")}
            if(settings.advanced)TextButton(onClick={preview(p.copy(importance="quiet"))}){Text("Stop preview")}
        }
        Text("Alarm mode uses Android’s alarm audio stream. Preview uses the current volume. DND exceptions remain in Android Settings; profile forwarding uses the receiving profile’s channel.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={Button(onClick={save(p)}){Text("Done")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}

@Composable internal fun TaskReminderDialog(model:Workspace,state:WorkspaceState,record:Record,device:DeviceActions,close:()->Unit) {
    val initial=reminderSet(record) ?: ReminderSet(if(record.data.text("due").isNotBlank())listOf(ReminderAlert(record.id,0)) else emptyList())
    var json by rememberSaveable(record.id){mutableStateOf(reminderData(initial).toString())};var error by remember{mutableStateOf<String?>(null)}
    var optionsJson by rememberSaveable(record.id){mutableStateOf(reminderOptionsData(reminderOptions(record) ?: ReminderOptions(ReminderAudience("assigned"))).toString())}
    val options=SyncJson.decodeFromString<ReminderOptions>(optionsJson)
    val rules=WireJson.decodeFromString<ReminderSet>(json);val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};val basis=remember{state.cache.snapshot!!.basis}
    val day=runCatching{LocalDate.parse(record.data.text("due").ifBlank{record.data.text("planned_for")})}.getOrDefault(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
    AlertDialog(onDismissRequest=close,title={Text("Reminders")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(record.data.text("title"));Text("Date reminders use 9 AM on the due or planned day. A recurring window works even without a due date.",style=MaterialTheme.typography.bodySmall)
        ReminderEditor(rules,day,model::newId,device.use24Hour,device.previewSound,reminderEditorSettings(model,state,options,true){optionsJson=reminderOptionsData(it).toString()}){json=reminderData(it).toString()}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button(enabled=!busy,onClick={busy=true;scope.launch{try{
        validateReminders(rules);val extra=prunedReminderOptions(options,rules)
        val data=if(state.cache.supportsRemindersV2()){validateReminderOptions(extra);obj("reminders" to reminderData(rules),"reminder_options" to reminderOptionsData(extra))}else obj("reminders" to reminderData(rules))
        model.enqueue("task.update",record.id,data,record.revision,basis);close();model.sync()
    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false}}}){Text("Save reminders")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
