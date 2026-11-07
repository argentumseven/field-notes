package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.fieldnotes.domain.*

@Composable internal fun ReminderTimeDialog(initial:ReminderAlert,extended:Boolean,close:()->Unit,save:(ReminderAlert)->Unit) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val parts=remember(initial.id){reminderTimeParts(initial)}
    var amount by rememberSaveable(initial.id){mutableStateOf(parts.amount.toString())}
    var unit by rememberSaveable(initial.id){mutableStateOf(parts.unit)}
    val choices=reminderTimeUnits.filterKeys{extended||it !in setOf("months","years")}.toList()
    val maximum=if(extended)reminderTimeLimits[unit] ?: 0 else 43200/(mapOf("minutes" to 1,"hours" to 60,"days" to 1440,"weeks" to 10080)[unit] ?: Int.MAX_VALUE)
    val count=amount.toIntOrNull();val valid=count!=null&&count in 0..maximum
    EditorDialog(onDismissRequest=close,title={Text("Reminder time")},text={ScrollableColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("How long before the start should this reminder arrive?")
        OutlinedTextField(amount,{amount=it.take(8)},label={Text("Amount before")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}),modifier=Modifier.fillMaxWidth())
        ViewChoice("Time unit",unit,choices){unit=it}
        Text("Months and years follow calendar dates. Other units count elapsed time. Zero means at the start.",style=MaterialTheme.typography.bodySmall)
        if(unit in setOf("months","years"))Text("Calendar timezone: "+(initial.lead_time?.zone ?: TimeZone.currentSystemDefault().id).substringAfterLast('/').replace('_',' '),style=MaterialTheme.typography.bodySmall)
        if(!extended)Text("Update the paired server for months, years and longer reminders.",style=MaterialTheme.typography.bodySmall)
        if(!valid)Text("Enter a whole number from 0 to $maximum.",color=MaterialTheme.colorScheme.error)
    }},confirmButton={Button(shape=ControlShape,enabled=valid,onClick={
        save(if(count==parts.amount&&unit==parts.unit)initial else withReminderTime(initial,count!!,unit,initial.lead_time?.zone ?: TimeZone.currentSystemDefault().id))
    }){Text("Save time")}},dismissButton={SecondaryButton(onClick=close){Text("Cancel")}})
}
