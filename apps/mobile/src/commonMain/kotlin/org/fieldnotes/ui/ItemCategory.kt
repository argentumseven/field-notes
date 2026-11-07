package org.fieldnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import org.fieldnotes.domain.*

@Composable internal fun ItemCategoryPicker(context:ItemContext,value:String,enabled:Boolean=true,changed:(String)->Unit) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    if(context.scoped)ViewChoice(context.label,value,context.options(value),enabled,changed)
    else OutlinedTextField(value,{changed(it.take(80))},label={Text("Item category · optional")},
        supportingText={Text("Use any label, or leave it uncategorized.")},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}))
    if(context.retained(value))Text("Saved label kept. Choose a category for this context only if you want to reclassify it.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
