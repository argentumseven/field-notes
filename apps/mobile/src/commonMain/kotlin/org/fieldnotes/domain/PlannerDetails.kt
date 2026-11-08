package org.fieldnotes.domain

fun Cache.supportsPlannerDetails()=compatibility?.let{!it.blocked&&"planner-details-v1" in it.features}==true
fun Record.isRecipe()=kind=="note"&&data.text("note_type")=="recipe"
fun Record.noteSearchText()=listOf("title","body","ingredients","instructions").joinToString(" "){data.text(it)}
