@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One editor shell: the body scrolls inside the actual available height, while
 * the actions remain outside that scroll and above the software keyboard. */
@Composable internal fun EditorDialog(
    onDismissRequest:()->Unit,
    confirmButton:@Composable ()->Unit,
    modifier:Modifier=Modifier,
    dismissButton:(@Composable ()->Unit)?=null,
    title:(@Composable ()->Unit)?=null,
    text:(@Composable ()->Unit)?=null,
) {
    val keyboard=LocalSoftwareKeyboardController.current
    val keyboardOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    Dialog(onDismissRequest=onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),contentAlignment=Alignment.Center) {
            Surface(modifier.padding(16.dp).widthIn(max=640.dp).fillMaxWidth().heightIn(max=(maxHeight-32.dp).coerceAtLeast(120.dp)),shape=MaterialTheme.shapes.large,tonalElevation=6.dp) {
                Column {
                    if(title!=null)Box(Modifier.fillMaxWidth().padding(20.dp)){ProvideTextStyle(MaterialTheme.typography.titleLarge){title()}}
                    if(text!=null)Box(Modifier.fillMaxWidth().weight(1f,fill=false).padding(horizontal=20.dp)){text()}
                    HorizontalDivider(Modifier.padding(top=12.dp))
                    FlowRow(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(keyboardOpen)SecondaryButton(onClick={keyboard?.hide()}){Text("Done typing")}
                        dismissButton?.invoke();confirmButton()
                    }
                }
            }
        }
    }
}
