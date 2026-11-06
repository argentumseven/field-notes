package org.fieldnotes.domain

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
class ProjectsGoldenTest {
    @Test fun projectsKeepWorkReferencesAndCategoryIndependent() {
        val file=listOf(Path.of("tests/fixtures/projects.json"),Path.of("../../tests/fixtures/projects.json")).first {Files.exists(it)}
        val fixture=WireJson.parseToJsonElement(Files.readString(file)).jsonObject
        val rows=WireJson.decodeFromJsonElement<List<Record>>(fixture.getValue("records"));val graph=ProjectGraph(rows);val project=graph.byId.getValue("p")
        assertEquals(fixture["expected"],WireJson.encodeToJsonElement(graph.rollup(project,fixture.text("today"))))
        assertEquals(fixture.getValue("planner").jsonArray.map {it.jsonPrimitive.content},graph.tasks.filter {graph.planner(it)}.map{it.id}.sorted())
        assertEquals("Bathroom repairs › Preparation",graph.path("w"));assertEquals("p",graph.project(graph.byId.getValue("shopping"))?.id);assertNull(graph.workProject(graph.byId.getValue("shopping")))
        val moved=ProjectGraph(rows.map {if(it.id=="w")it.copy(data=JsonObject(it.data+("project_id" to "q".j()))) else it})
        assertEquals(2,moved.rollup(project,fixture.text("today")).total);assertEquals(3,moved.rollup(moved.byId.getValue("q"),fixture.text("today")).total);assertEquals("home",moved.byId.getValue("doing").data.text("category"))
    }
}
