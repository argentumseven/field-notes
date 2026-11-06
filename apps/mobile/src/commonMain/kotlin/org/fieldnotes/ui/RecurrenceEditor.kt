@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import org.fieldnotes.domain.*

@Composable internal fun RecurrenceEditor(value:EventRepeat?,first:LocalDate,enabled:Boolean=true,changed:(EventRepeat?)->Unit) {
    fun seed()=EventRepeat(weekdays=listOf(first.dayOfWeek.isoDayNumber),dates=listOf(first.toString()),zone=TimeZone.currentSystemDefault().id)
    var custom by rememberSaveable { mutableStateOf(repeatPreset(value)=="custom") }
    var customUnit by rememberSaveable { mutableStateOf(if(value?.frequency=="monthly"&&value.interval>0&&value.interval%12==0)"yearly" else value?.frequency ?: "weekly") }
    val preset=if(custom&&value!=null)"custom" else repeatPreset(value)
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Repeating event",style=MaterialTheme.typography.titleSmall)
        ViewChoice("Repeat",preset,listOf("none" to "Does not repeat","daily" to "Every day","alternate" to "Every other day","weekdays" to "Weekdays","weekly" to "Weekly · choose days","fortnightly" to "Alternate weeks","monthly" to "Monthly","yearly" to "Every year","dates" to "Choose individual dates","custom" to "Custom interval"),enabled){key->
            custom=key=="custom"
            if(custom)customUnit=if(value?.frequency=="monthly"&&value.interval>0&&value.interval%12==0)"yearly" else value?.frequency ?: "weekly"
            if(key=="none")changed(null) else {val r=value ?: seed();changed(when(key){"alternate"->r.copy(frequency="daily",interval=2);"fortnightly"->r.copy(frequency="weekly",interval=2);"weekdays"->r.copy(frequency="weekly",interval=1,weekdays=(1..5).toList());"yearly"->r.copy(frequency="monthly",interval=12);"custom"->r;else->r.copy(frequency=key,interval=1)})}
        }
        if(value!=null) {
            if(custom) {
                val unit=customUnit
                ViewChoice("Repeat every",unit,listOf("daily" to "Days","weekly" to "Weeks","monthly" to "Months","yearly" to "Years","dates" to "Individual dates"),enabled){customUnit=it;changed(value.copy(frequency=if(it=="yearly")"monthly" else it,interval=if(it=="yearly")12 else 1))}
                if(value.frequency!="dates") {
                    val interval=if(unit=="yearly")value.interval/12 else value.interval
                    OutlinedTextField(interval.takeIf { it>0 }?.toString() ?: "",{if(it.length<=3&&it.all(Char::isDigit))changed(value.copy(interval=(it.toIntOrNull() ?: 0)*(if(unit=="yearly")12 else 1)))},label={Text("Every ${when(unit){"daily"->"days";"weekly"->"weeks";"yearly"->"years";else->"months"}}")},supportingText={Text(if(unit=="yearly")"1–30 years" else "1–365")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),enabled=enabled)
                }
            }
            if(value.frequency=="weekly")FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) { for(n in listOf(7,1,2,3,4,5,6))FilterChip(n in value.weekdays,{changed(value.copy(weekdays=if(n in value.weekdays)value.weekdays-n else (value.weekdays+n).sorted()))},{Text(DayOfWeek.entries[n-1].name.take(3).lowercase().replaceFirstChar { it.uppercase() })},enabled=enabled) }
            if(value.frequency=="dates")RepeatDateChoices("Occurrence dates",value.dates,first,enabled){changed(value.copy(dates=it))}
            DateField("Last repeat day · optional",value.until,enabled){changed(value.copy(until=it))}
            var exceptions by rememberSaveable { mutableStateOf(false) }
            SecondaryButton(onClick={exceptions=!exceptions}){Text("Skip dates (${value.excluded.size}) "+if(exceptions)"▴" else "▾")}
            if(exceptions)RepeatDateChoices("Skipped dates",value.excluded,first,enabled){changed(value.copy(excluded=it))}
            Text("Repeats in ${value.zone.replace('_',' ')}. February 29 repeats in leap years; other missing dates and nonexistent clock-change times are skipped.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val preview=runCatching {val today=Clock.System.now().toLocalDateTime(TimeZone.of(value.zone)).date;nextRepeatDates(value,first,today).joinToString(" · "){friendlyDate(it)} }.fold({"Next dates: "+it.ifBlank { "none with these repeat settings" }},{it.message ?: "Review the repeat pattern."})
            Text(preview,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        }
    } }
}
@Composable private fun RepeatDateChoices(title:String,dates:List<String>,first:LocalDate,enabled:Boolean,changed:(List<String>)->Unit) {
    var next by rememberSaveable(title){mutableStateOf(first.toString())}
    Text(title,style=MaterialTheme.typography.labelLarge)
    DateField("Choose a date",next,enabled,clearable=false){next=it}
    SecondaryButton(onClick={if(next !in dates)changed((dates+next).sorted())},enabled=enabled&&dates.size<128){Text("Add date")}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {for(date in dates)InputChip(selected=true,onClick={changed(dates-date)},label={Text(friendlyDate(LocalDate.parse(date))+" ×")},enabled=enabled)}
}
