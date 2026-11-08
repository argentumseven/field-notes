@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.datetime.TimeZone
import org.fieldnotes.domain.*

@Composable internal fun ReminderTimeDialog(initial:ReminderAlert,extended:Boolean,close:()->Unit,dayTiming:Boolean=false,use24Hour:Boolean=false,save:(ReminderAlert)->Unit) {
    val parts=remember(initial.id){reminderTimeParts(initial)}
    val originalDay=initial.day_time
    var calendarDay by rememberSaveable(initial.id){mutableStateOf(originalDay!=null)}
    var amount by rememberSaveable(initial.id){mutableStateOf((originalDay?.days_before?.let{if(it>0&&it%7==0)it/7 else it} ?: parts.amount).toString())}
    var unit by rememberSaveable(initial.id){mutableStateOf(originalDay?.let{if(it.days_before>0&&it.days_before%7==0)"weeks"else"days"} ?: parts.unit)}
    var clock by rememberSaveable(initial.id){mutableStateOf(originalDay?.time ?: "09:00")}
    val choices=reminderTimeUnits.filterKeys{if(calendarDay)it in setOf("days","weeks")else extended||it !in setOf("months","years")}.toList()
    val maximum=if(calendarDay)if(unit=="weeks")522 else 3660 else if(extended)reminderTimeLimits[unit] ?:0 else 43200/(mapOf("minutes" to 1,"hours" to 60,"days" to 1440,"weeks" to 10080)[unit] ?:Int.MAX_VALUE)
    val count=amount.toIntOrNull();val valid=count!=null&&count in 0..maximum
    EditorDialog(onDismissRequest=close,title={Text("Reminder time")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(dayTiming)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(!calendarDay,{calendarDay=false;unit="minutes";amount="0"},{Text("Before start")})
            FilterChip(calendarDay,{calendarDay=true;unit="days";amount="0"},{Text("Day & time")})
        }
        Text(if(calendarDay)"Choose the day and clock time."else"How long before the start?")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            val presets=when(unit){"minutes"->listOf(0,5,10,15,30);"hours"->listOf(0,1,2,3,6,12,24);else->listOf(0,1,2,3,4)}
            for(n in presets.filter{it<=maximum})FilterChip(count==n,{amount=n.toString()},{Text(if(calendarDay&&n==0)"Day of"else n.toString())})
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            SecondaryButton(onClick={amount=((count ?:0)-1).coerceAtLeast(0).toString()}){Text("−")}
            OutlinedTextField(amount,{amount=it.take(8)},label={Text("Amount before")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f))
            SecondaryButton(onClick={amount=((count ?:0)+1).coerceAtMost(maximum).toString()}){Text("+")}
        }
        ViewChoice("Time unit",unit,choices){unit=it;amount=(amount.toIntOrNull() ?:0).coerceAtMost(if(calendarDay)if(it=="weeks")522 else 3660 else reminderTimeLimits.getValue(it)).toString()}
        if(calendarDay)TimeField("At",clock,use24Hour){clock=it}
        Text(if(calendarDay)"Uses the event’s first day at your chosen local time, including all-day events."else"Months and years follow calendar dates. Other units count elapsed time.",style=MaterialTheme.typography.bodySmall)
        if(!valid)Text("Choose a whole number from 0 to $maximum.",color=MaterialTheme.colorScheme.error)
    }},confirmButton={Button(shape=ControlShape,enabled=valid,onClick={
        val zone=originalDay?.zone ?:initial.lead_time?.zone ?:TimeZone.currentSystemDefault().id
        save(if(calendarDay)withReminderDayTime(initial,count!!*(if(unit=="weeks")7 else 1),clock,zone)else withReminderTime(initial,count!!,unit,zone))
    }){Text("Save time")}},dismissButton={SecondaryButton(onClick=close){Text("Cancel")}})
}
