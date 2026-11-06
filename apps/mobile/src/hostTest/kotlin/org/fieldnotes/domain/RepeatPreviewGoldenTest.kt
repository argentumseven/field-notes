package org.fieldnotes.domain

import java.io.File
import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

class RepeatPreviewGoldenTest {
    @Test fun webAndNativeUseTheSameBoundedPreviewCases() {
        val cases=WireJson.parseToJsonElement(File("../../tests/fixtures/repeat-previews.json").readText()).jsonArray
        for(row in cases.map { it.jsonObject }) {
            val rule=EventRepeat(row.text("frequency"),row.number("interval").toInt(),
                (row["weekdays"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: emptyList(),
                (row["dates"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),row.text("until"),
                (row["excluded"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),"America/New_York")
            assertEquals(row.getValue("expected").jsonArray.map { it.jsonPrimitive.content },nextRepeatDates(rule,LocalDate.parse(row.text("first")),LocalDate.parse(row.text("from"))).map { it.toString() },row.text("name"))
        }
    }
}
