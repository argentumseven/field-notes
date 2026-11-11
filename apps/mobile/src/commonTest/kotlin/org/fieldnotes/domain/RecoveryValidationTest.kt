package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class RecoveryValidationTest {
    private val preview=RecoveryPreview("preview",9999999999,"epoch",1,"source",7,"Family checkpoint",true,change_count=3,changes=emptyList(),confirmation_phrase="RESTORE CHECKPOINT #7")
    @Test fun restoreCannotBuildAnEnvelopeWithoutTheTypedPhraseAndReview() {
        assertFailsWith<IllegalStateException>{preview.applyRequest("op","RESTORE CONTENT",true)}
        assertFailsWith<IllegalStateException>{preview.applyRequest("op","RESTORE CHECKPOINT #6",true)}
        assertFailsWith<IllegalStateException>{preview.applyRequest("op","RESTORE CHECKPOINT #7",false)}
        assertFailsWith<IllegalStateException>{preview.copy(confirmation_phrase="").applyRequest("op","",true)}
        val body=preview.applyRequest("op","RESTORE CHECKPOINT #7",true)
        assertEquals("RESTORE CHECKPOINT #7",body.text("confirmation"));assertEquals(JsonPrimitive(true),body["reviewed"])
        assertEquals("preview",body.text("preview_id"));assertEquals("op",body.text("operation_id"))
        assertFalse(body.containsKey("password"))
    }
    @Test fun deleteBindsTheSelectedIdentityAndHistoryRevisionWithoutIncludingContent() {
        val entry=RecoveryVersion("saved",9,at=123,actor_name="Owner",action="checkpoint.create",title="Before changes",can_delete=true)
        assertFailsWith<IllegalStateException>{entry.deleteRequest("epoch",2,13,"op","DELETE CHECKPOINT #8")}
        assertFailsWith<IllegalStateException>{entry.copy(can_delete=false).deleteRequest("epoch",2,13,"op","DELETE CHECKPOINT #9")}
        val body=entry.deleteRequest("epoch",2,13,"op","DELETE CHECKPOINT #9")
        assertEquals("saved",body.text("source"));assertEquals(13,body.number("history_revision"));assertEquals(2,body.number("generation"))
        assertFalse(body.containsKey("password"));assertFalse(body.containsKey("title"))
    }
}
