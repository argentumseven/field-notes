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

internal data class ReminderEditorSettings(val options:ReminderOptions=ReminderOptions(),val people:List<HouseholdPerson> = emptyList(),val user:String="",val advanced:Boolean=false,val assigned:Boolean=false,val allowLoud:Boolean=false,val enableLoud:()->Unit={},val changed:(ReminderOptions)->Unit={},val calendarTiming:Boolean=false,val dayTiming:Boolean=false,val flat:Boolean=false,val audioV2:Boolean=false,val allowNotification:Boolean=false,val enableNotification:()->Unit={},val testSound:(AlertProfile)->Unit={},val stopSound:()->Unit={},val intervalTiming:Boolean=false,val anchored:Boolean=true)
internal fun reminderEditorSettings(model:Workspace,state:WorkspaceState,options:ReminderOptions,assigned:Boolean=false,device:DeviceActions=DeviceActions(),changed:(ReminderOptions)->Unit)=ReminderEditorSettings(options,state.cache.people,state.cache.snapshot!!.actor.user_id,state.cache.supportsRemindersV2(),assigned,state.cache.preferences.allowAlarmVolumeOverride,{model.launch { model.preferences{it.copy(allowAlarmVolumeOverride=true)} }},changed,calendarTiming=state.cache.supportsReminderTiming(),dayTiming=state.cache.supportsReminderSchedule(),audioV2=state.cache.supportsReminderAudio(),allowNotification=state.cache.preferences.allowNotificationVolumeOverride,enableNotification={model.launch{model.preferences{it.copy(allowNotificationVolumeOverride=true)}}},testSound=device.testReminderSound,stopSound=device.stopAlarm,intervalTiming=state.cache.supportsReminderInterval())
@Composable internal fun ReminderEditor(value:ReminderSet,day:LocalDate,newId:()->String,use24Hour:Boolean=false,preview:(AlertProfile)->Unit={},settings:ReminderEditorSettings=ReminderEditorSettings(),changed:(ReminderSet)->Unit) {
    val emit:(ReminderSet)->Unit={changed(if(settings.intervalTiming)it.copy(timing_version=3)else if(settings.dayTiming)it.copy(timing_version=2)else if(settings.calendarTiming)it.copy(timing_version=1)else it)}
    var editingTime by remember {mutableStateOf<ReminderAlert?>(null)}
    var newTime by remember {mutableStateOf(false)}
    var expanded by rememberSaveable { mutableStateOf(settings.flat) }
    var addMenu by remember { mutableStateOf(false) }
    var soundFor by remember { mutableStateOf<String?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(!settings.flat)SecondaryButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) { Text("Reminders · ${value.alerts.size}"+(if(value.repeat!=null||value.interval!=null)" + recurring" else "")+if(expanded)" ▴" else " ▾") }
        if(expanded) {
            if(settings.advanced)ReminderAudienceEditor(settings)
            else Text("Basic reminders remain compatible with this server. Update the paired server to choose people, longer sounds and alarm mode.",style=MaterialTheme.typography.bodySmall)
            Text(if(settings.anchored)"Date reminders use this item’s scheduled time. Repeat every is independent of its dates." else "This item has no scheduled date. Use Repeat every for a nudge that keeps returning until you finish it or turn reminders off.",style=MaterialTheme.typography.bodySmall)
            if(!settings.anchored&&value.alerts.isNotEmpty())Text("${value.alerts.size} saved date reminders are paused until a date is set. Remove them if you only want repeating nudges.",style=MaterialTheme.typography.bodySmall)
            value.alerts.forEach { rule -> Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                val p=settings.options.playback[rule.id]?.applyTo(rule.profile) ?: rule.profile
                Column { Text(reminderTimeLabel(rule,use24Hour));Text("${(if(p.alarm)"Alarm" else "Notification")+" · "+p.importance.replaceFirstChar{it.uppercase()}} · ${reminderTones[p.sound] ?: p.sound} · ${if(p.alarm&&p.duration>0)"${p.duration} s" else "once"} · ${p.volume}%",style=MaterialTheme.typography.labelSmall) }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                SecondaryButton(onClick={editingTime=rule;newTime=false}){Text("Time")}
                SecondaryButton(onClick={soundFor=rule.id}){Text("Sound")}
                SecondaryButton(onClick={emit(value.copy(alerts=value.alerts.filterNot { it.id==rule.id }))}){Text("Remove")}
            } } }
            if(settings.anchored)Box { SecondaryButton(onClick={addMenu=true},enabled=value.alerts.size<8){Text("Add reminder")};DropdownMenu(addMenu,{addMenu=false}) {
                for(minutes in listOf(0,5,10,15,30,60,120,1440,2880,4320,10080,20160,30240,40320))DropdownMenuItem(text={Text(reminderTimeLabel(ReminderAlert("preview",minutes)))},onClick={emit(value.copy(alerts=value.alerts+ReminderAlert(newId(),minutes)));addMenu=false})
                DropdownMenuItem(text={Text("Custom time…")},onClick={editingTime=ReminderAlert(newId());newTime=true;addMenu=false})
            } }
            if(settings.intervalTiming) {
                Row(verticalAlignment=Alignment.CenterVertically){Switch(value.interval!=null,{enabled->emit(value.copy(interval=if(enabled)ReminderInterval(newId(),anchor_at=Clock.System.now().epochSeconds,zone=TimeZone.currentSystemDefault().id)else null,repeat=if(enabled)null else value.repeat))},modifier=Modifier.semantics{contentDescription="Repeat every interval"});Text("Repeat every",Modifier.padding(start=8.dp))}
                value.interval?.let {rule->
                    var amount by rememberSaveable(rule.id,rule.unit){mutableStateOf(rule.amount.toString())}
                    OutlinedTextField(amount,{amount=it.take(7);emit(value.copy(interval=rule.copy(amount=it.toIntOrNull() ?: 0)))},label={Text("Repeat interval")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for((unit,label) in reminderTimeUnits)FilterChip(rule.unit==unit,{emit(value.copy(interval=rule.copy(unit=unit,amount=rule.amount.coerceIn(1,reminderTimeLimits.getValue(unit)))))},{Text(label)})}
                    Text("First nudge one interval after saving. After a missed period, only one catch-up nudge is delivered. Months and years keep the original day where possible.",style=MaterialTheme.typography.bodySmall)
                    Text("${rule.importance.replaceFirstChar{it.uppercase()}} · ${rule.amount} ${rule.unit}",style=MaterialTheme.typography.labelMedium)
                    SecondaryButton(onClick={soundFor=rule.id}){Text("Repeating nudge sound & importance")}
                }
            } else if(!settings.anchored)Text("Update the paired server to use repeat intervals. Existing time windows remain available.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically) { Switch(value.repeat!=null,{checked -> emit(value.copy(repeat=if(checked) ReminderWindow(newId(),starts_on=day.toString(),zone=TimeZone.currentSystemDefault().id) else null,interval=if(checked)null else value.interval)) },modifier=Modifier.semantics {contentDescription="Repeat during a time window"});Text("Repeat during a time window",Modifier.padding(start=8.dp)) }
            value.repeat?.let { rule ->
                Text("These are reminders, not blocks of occupied calendar time.",style=MaterialTheme.typography.bodySmall)
                DateField("First reminder day",rule.starts_on,clearable=false){emit(value.copy(repeat=rule.copy(starts_on=it)))}
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(rule.weekdays.isEmpty(),{emit(value.copy(repeat=rule.copy(weekdays=emptyList())))},{Text("Once")})
                    FilterChip(rule.weekdays.isNotEmpty(),{emit(value.copy(repeat=rule.copy(weekdays=listOf(LocalDate.parse(rule.starts_on).dayOfWeek.isoDayNumber))))},{Text("Every week")})
                }
                if(rule.weekdays.isNotEmpty()) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { for(d in 1..7)FilterChip(d in rule.weekdays,{ val days=if(d in rule.weekdays)rule.weekdays-d else rule.weekdays+d;emit(value.copy(repeat=rule.copy(weekdays=days.ifEmpty { listOf(d) }.sorted()))) },{Text(DayOfWeek(d).name.take(3).lowercase().replaceFirstChar { it.uppercase() })}) }
                    SecondaryButton(onClick={emit(value.copy(repeat=rule.copy(weekdays=(1..7).toList())))}){Text("Every day")}
                    DateField("Last reminder day · optional",rule.ends_on){emit(value.copy(repeat=rule.copy(ends_on=it)))}
                }
                TimeField("From",rule.from,use24Hour){emit(value.copy(repeat=rule.copy(from=it)))}
                TimeField("Until",rule.until,use24Hour){emit(value.copy(repeat=rule.copy(until=it)))}
                var hours by rememberSaveable(rule.id){mutableStateOf(rule.every_minutes>=60&&rule.every_minutes%60==0)}
                var interval by rememberSaveable(rule.id){mutableStateOf((if(hours)rule.every_minutes/60 else rule.every_minutes).toString())}
                OutlinedTextField(interval,{interval=it.take(4);emit(value.copy(repeat=rule.copy(every_minutes=(it.toIntOrNull() ?: 0)*(if(hours)60 else 1))))},label={Text("Repeat every")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { for(h in listOf(false,true))FilterChip(hours==h,{
                    hours=h;val next=if(h)(rule.every_minutes/60).coerceAtLeast(1) else rule.every_minutes.coerceAtLeast(1);interval=next.toString();emit(value.copy(repeat=rule.copy(every_minutes=next*(if(h)60 else 1))))
                },{Text(if(h)"Hours" else "Minutes")}) }
                Text("${rule.zone.replace('_',' ')} · windows can continue past midnight",style=MaterialTheme.typography.labelSmall)
                SecondaryButton(onClick={soundFor=rule.id}){Text("Recurring reminder sound & importance")}
            }
            if(value.alerts.isEmpty()&&value.repeat==null&&value.interval==null)Text("No reminders for this item.",style=MaterialTheme.typography.bodySmall)
        }
    } }
    editingTime?.let{initial->ReminderTimeDialog(initial,settings.calendarTiming,{editingTime=null},dayTiming=settings.dayTiming,use24Hour=use24Hour){updated->
        emit(value.copy(alerts=if(newTime)value.alerts+updated else value.alerts.map{if(it.id==updated.id)updated else it}));editingTime=null
    }}
    soundFor?.let { id ->
        val alert=value.alerts.find { it.id==id };val rule=value.repeat?.takeIf { it.id==id };val interval=value.interval?.takeIf{it.id==id };val base=alert?.profile ?: rule?.profile ?: interval?.profile
        val profile=base?.let { settings.options.playback[id]?.applyTo(it) ?: it }
        if(profile!=null) SoundProfileDialog(profile,preview,settings,{soundFor=null}) { p ->
            emit(if(alert!=null)value.copy(alerts=value.alerts.map { if(it.id==id)it.copy(importance=p.importance,sound=legacyTone(p.sound),volume=p.volume,vibrate=p.vibrate) else it }) else if(rule!=null)value.copy(repeat=rule.copy(importance=p.importance,sound=legacyTone(p.sound),volume=p.volume,vibrate=p.vibrate)) else value.copy(interval=interval!!.copy(importance=p.importance,sound=legacyTone(p.sound),volume=p.volume,vibrate=p.vibrate)))
            if(settings.advanced)settings.changed(prunedReminderOptions(settings.options,value).copy(playback=prunedReminderOptions(settings.options,value).playback+(id to playbackOptions(p)),playback_version=if(settings.audioV2)2 else settings.options.playback_version))
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
    EditorDialog(onDismissRequest=close,title={Text("Sound & importance")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Importance")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(level in listOf("quiet","normal","urgent"))FilterChip(p.importance==level,{p=p.copy(importance=level,alarm=if(level=="quiet")false else p.alarm,unmute=if(level=="quiet")false else p.unmute,notificationUnmute=if(level=="quiet")false else p.notificationUnmute)},{Text(level.replaceFirstChar { it.uppercase() })})}
        if(p.importance!="quiet") {
            if(settings.advanced) {
                Text("Delivery mode",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(!p.alarm,{p=p.withDeliveryMode(false)},{Text("Notification")})
                    FilterChip(p.alarm,{p=p.withDeliveryMode(true)},{Text("Alarm")})
                }
            }
            Text(if(p.alarm)(if(p.duration==0)"Alarm · plays once and shows on screen with Stop" else "Alarm · rings for the chosen time or until you press Stop, and shows on screen") else "Notification sound · one short sound")
            val choices=if(p.alarm)alarmTones else notificationTones.filterKeys{settings.advanced||it in setOf("chime","bell","pulse")}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for((tone,name) in choices)FilterChip(p.sound==tone,{p=p.copy(sound=tone)},{Text(name)})}
            if(p.sound !in choices)Text("Saved sound: ${reminderTones[p.sound] ?: p.sound}. Kept until you choose a replacement.",style=MaterialTheme.typography.bodySmall)
            if(settings.advanced) {
                if(p.alarm) {
                    Text("Ring for")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(seconds in listOf(0,5,10,15,30,60))FilterChip(p.duration==seconds,{p=p.copy(duration=seconds)},{Text(if(seconds==0)"Play once" else "$seconds s")})}
                    Row(verticalAlignment=Alignment.CenterVertically){Switch(p.unmute,{p=p.copy(unmute=it)},modifier=Modifier.semantics {contentDescription="Temporarily raise alarm volume"});Text("Temporarily raise alarm volume",Modifier.padding(start=8.dp))}
                    if(p.unmute) {
                        Text("Alarm volume · ${p.alarmVolume}%")
                        Slider(p.alarmVolume.toFloat(),{p=p.copy(alarmVolume=it.toInt())},valueRange=25f..100f,steps=2)
                        if(!settings.allowLoud)SecondaryButton(onClick=settings.enableLoud){Text("Enable loud alarms on this phone")} else Text("Temporary alarm-volume control is enabled on this phone.",style=MaterialTheme.typography.bodySmall)
                    }
                } else if(settings.audioV2) {
                    Row(verticalAlignment=Alignment.CenterVertically){Switch(p.notificationUnmute,{p=p.copy(notificationUnmute=it)},modifier=Modifier.semantics {contentDescription="Temporarily raise notification volume"});Text("Temporarily raise notification volume",Modifier.padding(start=8.dp))}
                    if(p.notificationUnmute) {
                        Text("Notification volume · ${p.notificationVolume}%")
                        Slider(p.notificationVolume.toFloat(),{p=p.copy(notificationVolume=it.toInt())},valueRange=25f..100f,steps=2)
                        if(!settings.allowNotification)SecondaryButton(onClick=settings.enableNotification){Text("Enable notification volume control on this phone")}
                    }
                }
                Text("Restores the previous volume afterward. Later manual changes take priority. Silent, Vibrate and DND remain under Android control.",style=MaterialTheme.typography.bodySmall)
            }
            Text(if(p.alarm)"Sound level · relative to alarm volume" else "Sound level · relative to notification volume")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){for(level in listOf(25,50,75,100))FilterChip(p.volume==level,{p=p.copy(volume=level)},{Text("$level%")})}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(p.vibrate,{p=p.copy(vibrate=it)},modifier=Modifier.semantics {contentDescription="Vibration"});Text("Vibration",Modifier.padding(start=8.dp))}
            SecondaryButton(onClick={preview(p)}){Text("Preview sound")}
            if(settings.advanced) {
                SecondaryButton(onClick={settings.testSound(p)}){Text("Test on this phone")}
                SecondaryButton(onClick={preview(p.copy(importance="quiet"));settings.stopSound()}){Text("Stop sound and restore volume")}
            }
        }
        Text("Alarm mode uses Android’s alarm audio stream. Preview uses the current volume. DND exceptions remain in Android Settings; profile forwarding uses the receiving profile’s channel.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={Button(shape=ControlShape,onClick={save(p)}){Text("Done")}},dismissButton={SecondaryButton(onClick=close){Text("Cancel")}})
}

@Composable internal fun TaskReminderDialog(model:Workspace,state:WorkspaceState,record:Record,device:DeviceActions,close:()->Unit) {
    val initial=reminderSet(record) ?: ReminderSet(if(record.data.text("due").isNotBlank()||record.kind=="note"&&record.data.number("remind_at")>0)listOf(ReminderAlert(record.id,0)) else emptyList())
    var json by rememberSaveable(record.id){mutableStateOf(reminderData(initial).toString())};var error by remember{mutableStateOf<String?>(null)}
    var optionsJson by rememberSaveable(record.id){mutableStateOf(reminderOptionsData(reminderOptions(record) ?: ReminderOptions()).toString())}
    val options=SyncJson.decodeFromString<ReminderOptions>(optionsJson)
    val rules=WireJson.decodeFromString<ReminderSet>(json);val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};val basis=remember{state.cache.snapshot!!.basis}
    val day=runCatching{LocalDate.parse(record.data.text("due").ifBlank{record.data.text("planned_for")})}.getOrDefault(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
    EditorDialog(onDismissRequest=close,title={Text("Reminders")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(record.data.text("title"));if(record.kind=="note")Text("Adding repeating nudges keeps this note in Later & reminders.",style=MaterialTheme.typography.bodySmall)
        ReminderEditor(rules,day,model::newId,device.use24Hour,device.previewSound,reminderEditorSettings(model,state,options,true,device){optionsJson=reminderOptionsData(it).toString()}.copy(anchored=hasReminderAnchor(record),flat=true)){json=reminderData(it).toString()}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button(shape=ControlShape,enabled=!busy,onClick={busy=true;scope.launch{try{
        val savedRules=rules
        require(hasReminderAnchor(record)||savedRules.alerts.isEmpty()||savedRules.interval!=null||savedRules.repeat!=null){"This item has no date. Choose Repeat every, a time window, or remove the paused date reminders."}
        validateReminders(savedRules);val extra=prunedReminderOptions(options,rules)
        val data=if(state.cache.supportsRemindersV2()){validateReminderOptions(extra);obj("reminders" to reminderData(savedRules),"reminder_options" to reminderOptionsData(extra))}else obj("reminders" to reminderData(savedRules))
        val payload=if(record.kind=="note"&&(savedRules.interval!=null||savedRules.repeat!=null))kotlinx.serialization.json.JsonObject(data+("on_radar" to kotlinx.serialization.json.JsonPrimitive(true)))else data
        model.enqueue(record.kind+".update",record.id,payload,record.revision,basis);model.requestSync();close()
    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}finally{busy=false}}}){Text("Save reminders")}},dismissButton={SecondaryButton(onClick=close){Text("Cancel")}})
}
