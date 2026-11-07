package org.fieldnotes.domain

import kotlinx.serialization.json.*

const val ITEM_CONTEXT_FEATURE="item-context-v1"
fun Cache.supportsItemContexts()=compatibility?.let {!it.blocked&&ITEM_CONTEXT_FEATURE in it.features}==true

data class ItemContext(val key:String,val label:String,val choices:List<String>) {
    val scoped get()=choices.isNotEmpty()
    fun retained(value:String)=value.isNotBlank()&&scoped&&value !in choices
    fun options(value:String)=listOf("" to "Uncategorized")+choices.map {it to it}+
        if(retained(value))listOf(value to "$value · saved label")else emptyList()
}
/** A list is an explicit boundary. Custom/untyped lists never acquire a
 * restriction from a containing project or from an item's old broad category. */
fun itemContext(data:JsonObject,parent:Record?):ItemContext {
    val key=if(parent?.kind=="list")when(parent.data.text("template","general")) {
        "groceries"->"groceries"
        "shopping"->"shopping"
        "general"->categoryKey(parent.data.text("category","general"))
        else->"general"
    } else categoryKey(data.text("category",parent?.data?.text("category","general") ?: "general"))
    return itemContextProfiles[key] ?: itemContextProfiles.getValue("general")
}
fun itemCategory(record:Record)=record.data.text("item_category")
fun itemCategoryMatches(value:String,filter:String)=when(filter){"all"->true;"none"->value.isBlank();else->filter=="value:$value"}
fun itemCategoryFilters(records:List<Record>,selected:String="all")=listOf("all" to "All item categories","none" to "Uncategorized")+
    (records.map(::itemCategory)+listOfNotNull(selected.takeIf{it.startsWith("value:") }?.removePrefix("value:")))
        .filter {it.isNotBlank()}.distinct().sortedBy {it.lowercase()}.map {"value:$it" to it}
