package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*

@Composable internal fun DataTransferCard(cache:Cache,device:DeviceActions) {
    var open by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Export & import",style=MaterialTheme.typography.titleMedium)
        Text("Encrypted content files you can open on your phone or in the web manager. Existing items and pairings are kept.",style=MaterialTheme.typography.bodySmall)
        SecondaryButton(onClick={open=true}){Text("Manage content files")}
    }}
    if(open)DataTransferDialog(cache,device){open=false}
}

@Composable private fun DataTransferDialog(cache:Cache,device:DeviceActions,close:()->Unit) {
    val state=device.transfer;var exporting by remember{mutableStateOf(false)};var password by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};var importPassword by remember{mutableStateOf("")}
    var adminPassword by remember { mutableStateOf("") }
    var removing by remember{mutableStateOf<String?>(null)}
    val writable=cache.snapshot?.actor?.role!="viewer"
    EditorDialog(onDismissRequest={if(!state.busy)close()},title={Text("Export & import")},text={ScrollableColumn {
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.message.isNotEmpty())Text(state.message,style=MaterialTheme.typography.bodyMedium)
        Text("Encrypted export",style=MaterialTheme.typography.titleMedium)
        Text("Includes this phone’s cached planner, images and retained offline changes. Missing images must be retrieved before the file can finish. Use the server for a full current household export.",style=MaterialTheme.typography.bodySmall)
        SecondaryButton(onClick={exporting=!exporting},enabled=!state.busy&&cache.snapshot!=null){Text("Export content…")}
        if(exporting){
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Export passphrase")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
            OutlinedTextField(confirm,{confirm=it},Modifier.fillMaxWidth(),label={Text("Confirm passphrase")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
            Text("At least 12 characters. Keep this passphrase: the server cannot recover the exported file.",style=MaterialTheme.typography.bodySmall)
            Button(shape=ControlShape,onClick={device.exportData(password);password="";confirm="";exporting=false},enabled=!state.busy&&password==confirm&&password.encodeToByteArray().size in 12..256){Text("Choose where to save")}
        }
        HorizontalDivider();Text("Import with a preview",style=MaterialTheme.typography.titleMedium)
        Text("Matching items are skipped. Different versions are kept as alternatives for review. No current item is overwritten.",style=MaterialTheme.typography.bodySmall)
        SecondaryButton(onClick=device.chooseImport,enabled=!state.busy&&writable){Text("Choose encrypted file…")}
        if(state.fileName.isNotEmpty())Text(state.fileName,style=MaterialTheme.typography.labelLarge)
        if(state.fileName.isNotEmpty()&&state.preview==null){
            OutlinedTextField(importPassword,{importPassword=it},Modifier.fillMaxWidth(),label={Text("Archive passphrase")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
            Button(shape=ControlShape,onClick={device.previewImport(importPassword);importPassword=""},enabled=!state.busy&&importPassword.isNotEmpty()){Text("Unlock and preview")}
        }
        state.preview?.let{preview->
            Text("${preview.number("new_items")} new · ${preview.number("already_present")} already present · ${preview.number("retained_changes")} retained changes",style=MaterialTheme.typography.titleSmall)
            for(raw in preview["people"]!!.jsonArray){val person=raw.jsonObject;var target by remember(person.text("id")){mutableStateOf(person.text("target"))};ViewChoice("Archived ${person.text("name")} →",target,cache.people.filter{it.can_assign}.map{it.id to it.name},enabled=!state.busy){target=it;device.mapImportPerson(person.text("id"),it)}}
            if(preview.number("new_categories")>0){
                Text("${preview.number("new_categories")} missing category definitions will be imported. Existing categories keep their names and archive settings.")
                OutlinedTextField(adminPassword,{adminPassword=it},Modifier.fillMaxWidth(),label={Text("Administrator password")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                SecondaryButton(onClick=device.reviewImport,enabled=!state.busy){Text("Review again")}
                Button(shape=ControlShape,onClick={device.commitImport(adminPassword);adminPassword=""},enabled=!state.busy&&state.reviewed&&(preview.number("new_categories")==0L||adminPassword.isNotEmpty())){Text("Import items")}
            }
        }
        if(state.fileName.isNotEmpty())SecondaryButton(onClick=device.cancelImport,enabled=!state.busy){Text("Cancel preview")}
        HorizontalDivider();Text("Recovered drafts & alternatives",style=MaterialTheme.typography.titleMedium)
        SecondaryButton(onClick=device.refreshRecovered,enabled=!state.busy){Text("Load recovered changes")}
        val recovered=if(state.recovered.isNotEmpty())state.recovered else cache.recoveredImports
        if(recovered.isEmpty())Text("Imported alternatives are kept here for review. If a task’s former collection is missing, its copy goes to Inbox. Identical current items are reused.",style=MaterialTheme.typography.bodySmall)
        for(item in recovered){
            val payload=item["payload"]?.jsonObject?:continue;val record=payload["record"] as? JsonObject;val command=payload["command"] as? JsonObject
            val data=JsonObject((record?.get("data") as? JsonObject).orEmpty()+(command?.get("data") as? JsonObject).orEmpty());val kind=record?.text("kind")?.takeIf{it.isNotBlank()}?:command?.text("action")?.substringBefore('.')?:"note"
            OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(item.text("title","Recovered change"),style=MaterialTheme.typography.titleSmall)
                payload.text("reason").takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                for(key in listOf("body","notes","quantity","store","link_url","price"))data.text(key).takeIf{it.isNotBlank()}?.let{Text(if(key in setOf("body","notes"))it else "$key: $it",style=MaterialTheme.typography.bodySmall)}
                var parent by remember(item.text("id")){mutableStateOf(data.text("task_id").takeIf{id->cache.snapshot?.records?.any{it.id==id&&it.kind=="task"}==true}.orEmpty())}
                if(kind in setOf("image","comment"))ViewChoice("Attach to task",parent,listOf("" to "Choose a task")+plannerTasks(cache).map{it.record.id to it.record.data.text("title")}){parent=it}
                if(item["has_image"]==JsonPrimitive(true))Text("Attached image is retained in the encrypted archive.",style=MaterialTheme.typography.bodySmall)
                SecondaryButton(onClick={removing=item.text("id")},enabled=!state.busy&&writable,dangerous=true){Text("Remove recovered copy")}
                SecondaryButton(onClick={device.restoreRecovered(item.text("id"),parent.takeIf{it.isNotEmpty()})},enabled=!state.busy&&writable&&(kind !in setOf("image","comment")||parent.isNotEmpty())){Text("Restore as a separate item")}
            }}
        }
    }},confirmButton={SecondaryButton(onClick=close,enabled=!state.busy){Text("Close")}})
    if(removing!=null)EditorDialog(onDismissRequest={removing=null},title={Text("Remove recovered copy?")},text={Text("Current planner items stay. Keep an encrypted export if you still need this alternative; existing backups are unchanged.")},confirmButton={Button(shape=ControlShape,onClick={device.discardRecovered(removing!!);removing=null}){Text("Remove copy")}},dismissButton={SecondaryButton(onClick={removing=null}){Text("Keep it")}})

}
