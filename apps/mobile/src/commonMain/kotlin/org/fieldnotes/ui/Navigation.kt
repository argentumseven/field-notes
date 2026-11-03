package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun DrawerGroup(title:String,views:List<String>,selected:String,initiallyOpen:Boolean=true,navigate:(String)->Unit) {
    var expanded by rememberSaveable(title){mutableStateOf(initiallyOpen)}
    TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().testTag("group-"+title.lowercase())) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge);Text(if(expanded)"▴" else "▾")
    }
    if(expanded) for(view in views)NavigationDrawerItem(modifier=Modifier.testTag("drawer-"+view.lowercase().replace(" ","-")),label={Text(view)},selected=selected==view,onClick={navigate(view)})
    Spacer(Modifier.height(8.dp))
}
