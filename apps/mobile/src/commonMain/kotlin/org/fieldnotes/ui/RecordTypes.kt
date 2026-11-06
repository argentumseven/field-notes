@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fieldnotes.domain.*

@Composable internal fun ViewChoice(label:String,value:String,choices:List<Pair<String,String>>,enabled:Boolean=true,changed:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(onClick={expanded=true},enabled=enabled){Text("$label: "+(choices.firstOrNull { it.first==value }?.second ?: value)+" ▾")}
        DropdownMenu(expanded,{expanded=false}) { for((key,title) in choices)DropdownMenuItem(text={Text(title)},onClick={expanded=false;changed(key)}) }
    }
}
@Composable internal fun CategoryPicker(value:String,enabled:Boolean=true,changed:(String)->Unit)=ViewChoice("Category",value,categoryChoices(value),enabled,changed)
@Composable internal fun RecordBadges(record:Record,parent:Record?=null) {
    val category=categoryLabel(record).takeUnless { it=="General" } ?: parent?.let(::categoryLabel)
    val kind=if(parent?.kind=="list")when(parent.data.text("template")){"groceries"->"Grocery item";"shopping"->"Shopping item";else->"List item"} else recordTypeLabel(record)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        for(label in listOfNotNull(kind,category?.takeUnless { it=="General" }))Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.extraSmall,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Text(label,Modifier.padding(horizontal=7.dp,vertical=3.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
    }
}
