package org.fieldnotes.domain

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class ItemContextGoldenTest {
    @Test fun scopedAndUnrestrictedInheritanceMatchesWebAndServer() {
        val file=listOf(Path.of("tests/fixtures/item-contexts.json"),Path.of("../../tests/fixtures/item-contexts.json")).first{Files.exists(it)}
        val profilesFile=listOf(Path.of("shared/item-contexts.json"),Path.of("../../shared/item-contexts.json")).first{Files.exists(it)}
        val profiles=WireJson.parseToJsonElement(Files.readString(profilesFile)).jsonObject
        for(value in WireJson.parseToJsonElement(Files.readString(file)).jsonArray) {
            val c=value.jsonObject
            val parent=(c["parent"] as? JsonObject)?.let{Record("parent",it.text("kind"),1,it.getValue("data").jsonObject)}
            assertEquals(c.text("expected"),itemContext(c.getValue("data").jsonObject,parent).key,c.text("name"))
            assertEquals(profiles.getValue(c.text("expected")).jsonObject.getValue("choices").jsonArray.map{it.jsonPrimitive.content},itemContext(c.getValue("data").jsonObject,parent).choices,c.text("name"))
        }
        val grocery=itemContext(obj(),Record("g","list",1,obj("template" to "groceries".j())))
        assertFalse("Work" in grocery.choices)
        assertTrue("Produce" in grocery.choices)
        assertEquals("A saved label · saved label",grocery.options("A saved label").last().second)
        assertEquals("Uncategorized",grocery.options("").first().second)
        assertTrue(itemCategoryMatches("*","value:*"))
        assertFalse(itemCategoryMatches("Other","value:*"))
        assertTrue(itemCategoryMatches("","none"))
        val task=Record("t","task",1,obj("item_category" to "学ぶ".j()))
        assertEquals("学ぶ",itemCategory(task))
        assertTrue(itemCategoryFilters(listOf(task,task)).count{it.first=="value:学ぶ"}==1)
    }
}
