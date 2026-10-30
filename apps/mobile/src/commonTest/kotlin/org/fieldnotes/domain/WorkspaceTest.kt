package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private val actor = Actor("u1", "Owner", "owner", "s1")
private val task = Record("task", "task", 1, obj("title" to "Kitchen 🪴".j(), "notes" to "Measure first".j(), "status" to "todo".j(), "quadrant" to "do".j(), "due" to "2026-10-30".j()))
private fun initial() = Cache(keys = obj("private_key" to "private-test-key".j()), config = obj("address" to "test".j()), token = "token", snapshot = Snapshot(1, "epoch", 1, 1, listOf(task), actor))
private class MemoryVault(var value: Cache = initial()) : Vault {
    var failing = false
    override suspend fun load() = value
    override suspend fun save(cache: Cache) { if (failing) error("Disk is full"); value = WireJson.decodeFromString(WireJson.encodeToString(cache)) }
    override suspend fun clearImages() {}
}
private class FakeTransport(var snapshot: Snapshot = initial().snapshot!!) : Transport {
    var networkFailure = false
    var rejection: Int? = null
    var expired = false
    var sent = mutableListOf<Command>()
    var results = mutableMapOf<String, JsonObject>()
    var loseReply = false
    override suspend fun keys() = obj("private_key" to "private-test-key".j())
    override suspend fun configure(configuration: JsonObject) {}
    override suspend fun stop() {}
    override suspend fun exchange(path: String, token: String?, body: JsonObject?, serverKey: String?): Reply {
        if (networkFailure) error("Offline")
        if (path == "/api/login") return Reply(200, obj("token" to "new-token".j(), "actor" to WireJson.encodeToJsonElement(actor)).toString())
        if (expired) return Reply(401, obj("error" to "Session expired".j()).toString())
        if (path == "/api/snapshot") return Reply(200, WireJson.encodeToString(snapshot))
        val c = WireJson.decodeFromJsonElement<Command>(body!!)
        sent += c
        results[c.operation_id]?.let { return Reply(200, it.toString()) }
        rejection?.let { return Reply(it, obj("error" to "Rejected by server".j()).toString()) }
        val old = snapshot.records.firstOrNull { it.id == c.target }
        if (old != null && old.revision != c.expected_revision) return Reply(409, obj("error" to "Revision conflict".j()).toString())
        val r = if (old == null) Record(c.target, c.action.substringBefore('.'), 1, c.data) else old.copy(revision = old.revision+1, data = JsonObject(old.data+c.data))
        snapshot = snapshot.copy(records = snapshot.records.filterNot { it.id == r.id }+r, sequence = snapshot.sequence+1)
        val response = obj("record" to WireJson.encodeToJsonElement(r), "sequence" to snapshot.sequence.j())
        results[c.operation_id] = response
        if (loseReply) { loseReply = false; error("Response lost after commit") }
        return Reply(200, response.toString())
    }
}
private fun model(v: MemoryVault, t: FakeTransport, scope: CoroutineScope): Workspace {
    var next = 0
    return Workspace(t, v, { "op-${next++}" }, scope)
}
class WorkspaceTest {
    @Test fun diskFailureDoesNotPublishAnUnsavedCommand() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();v.failing=true
        assertFailsWith<IllegalStateException> { m.enqueue("task.start","task",obj(),1) }
        assertTrue(m.state.value.cache.outbox.isEmpty());assertTrue(t.sent.isEmpty())
    }
    @Test fun offlineCommandsSurviveRestartAndDisplayProvisionalState() = runTest {
        val v=MemoryVault();val t=FakeTransport();var m=model(v,t,backgroundScope);m.initialize()
        m.enqueue("task.start","task",obj(),1);t.networkFailure=true;m.sync()
        assertTrue(projectRecords(m.state.value.cache).single().pending);assertEquals("in_progress",projectRecords(m.state.value.cache).single().record.data.text("status"))
        m=model(v,t,backgroundScope);m.initialize();assertEquals(1,m.state.value.cache.outbox.size)
    }
    @Test fun ambiguousCommitRetriesTheSameOperationWithoutDuplicatingIt() = runTest {
        val v=MemoryVault();val t=FakeTransport();var m=model(v,t,backgroundScope);m.initialize();m.enqueue("task.update","task",obj("notes" to "Keep both 🌱".j()),1)
        t.loseReply=true;m.sync();assertEquals(1,m.state.value.cache.outbox.size)
        m=model(v,t,backgroundScope);m.initialize();m.sync()
        assertEquals(t.sent[0].operation_id,t.sent[1].operation_id);assertEquals(1,t.results.size);assertTrue(m.state.value.cache.outbox.isEmpty())
        assertEquals("Keep both 🌱",m.state.value.cache.snapshot!!.records.single().data.text("notes"))
    }
    @Test fun dependentEditsUseOnlyTheAcceptedPredecessorsRevision() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        m.enqueue("task.update","task",obj("notes" to "First".j()),1);m.enqueue("task.update","task",obj("notes" to "Second".j()),1);m.sync()
        assertEquals(listOf(1L,2L),t.sent.map{it.expected_revision});assertTrue(m.state.value.cache.conflicts.isEmpty())
    }
    @Test fun rejectedPredecessorKeepsBothDraftsAndDoesNotSendItsDependent() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        m.enqueue("task.update","task",obj("notes" to "First".j()),1);m.enqueue("task.update","task",obj("notes" to "Second".j()),1);t.rejection=409;m.sync()
        assertEquals(1,t.sent.size);assertEquals(2,m.state.value.cache.conflicts.size);assertTrue(m.state.value.cache.outbox.isEmpty())
    }
    @Test fun workspaceResetKeepsOldEditsAsConflictsWithoutUploading() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();m.enqueue("task.update","task",obj("notes" to "Private draft".j()),1)
        t.snapshot=t.snapshot.copy(generation=2,records=emptyList());m.sync()
        assertTrue(t.sent.isEmpty());assertEquals("Private draft",m.state.value.cache.conflicts.single().command.data.text("notes"))
    }
    @Test fun restoredEpochRetainsUnsavedDeletedTaskDrafts() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();m.saveDraft("task",1,obj("title" to "Recover me".j()))
        t.snapshot=t.snapshot.copy(epoch="restored",records=emptyList());m.sync()
        assertTrue(m.state.value.cache.drafts.isEmpty());assertEquals("Recover me",m.state.value.cache.conflicts.single().command.data.text("title"))
        assertFailsWith<IllegalStateException> { m.resolve(m.state.value.cache.conflicts.single().command.operation_id,true) }
    }
    @Test fun expiredSessionPromptsLoginAndKeepsQueuedChanges() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();m.enqueue("task.update","task",obj("notes" to "Retain".j()),1);t.expired=true;m.sync()
        assertTrue(m.state.value.needsLogin);assertEquals(1,m.state.value.cache.outbox.size);assertTrue(m.state.value.cache.conflicts.isEmpty())
    }
    @Test fun viewerCannotQueueChangesOffline() = runTest {
        val v=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(actor=actor.copy(role="viewer"))));val m=model(v,FakeTransport(),backgroundScope);m.initialize()
        assertFailsWith<IllegalStateException> { m.enqueue("task.update","task",obj(),1) }
    }
    @Test fun draftsBecomeCommandsInOneDurableWrite() = runTest {
        val v=MemoryVault();val m=model(v,FakeTransport(),backgroundScope);m.initialize();m.saveDraft("task",1,obj("notes" to "Durable".j()));m.submitDraft("task")
        assertTrue(v.value.drafts.isEmpty());assertEquals("Durable",v.value.outbox.single().command.data.text("notes"))
    }
    @Test fun remindersAreLocalBoundedAndDeduplicated() {
        val c=initial().copy(preferences=Preferences(reminders=true));assertTrue(dueReminderKeys(c,"2026-10-30",8).isEmpty())
        val keys=dueReminderKeys(c,"2026-10-30",9);assertEquals(setOf("task:2026-10-30"),keys);assertTrue(dueReminderKeys(c.copy(reminded=keys),"2026-10-31",10).isEmpty())
        assertTrue(dueReminderKeys(c,"2026-10-29",10).isEmpty())
    }    @Test fun editorAndPhotoPickerBasisCannotBeRetaggedAcrossRestore() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();val basis=m.state.value.cache.snapshot!!.basis
        t.snapshot=t.snapshot.copy(epoch="restored");m.sync()
        m.saveDraft("task",1,obj("notes" to "Old editor".j()),basis);m.submitDraft("task");m.sync()
        assertTrue(t.sent.isEmpty());assertEquals("epoch",m.state.value.cache.conflicts.single().command.epoch)
    }
    @Test fun editsDuringAnOwnedWriteAdvanceOnlyThroughItsAcknowledgement() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        m.enqueue("task.update","task",obj("notes" to "First save".j()),1)
        m.saveDraft("task",1,obj("notes" to "Typed while pending".j()));m.sync()
        assertEquals(2,m.state.value.cache.drafts["task"]!!.revision)
        m.saveDraft("task",1,obj("notes" to "Later keystroke".j()));m.submitDraft("task");m.sync()
        assertEquals(2,t.sent.last().expected_revision);assertTrue(m.state.value.cache.conflicts.isEmpty())
    }
    @Test fun endpointChangesPreserveIdentityAndOfflineCommands() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        m.enqueue("task.start","task",obj(),1);val command=m.state.value.cache.outbox.single().command;val keys=m.state.value.cache.keys
        m.updateEndpoint("192.168.1.10:51831")
        assertEquals(keys,m.state.value.cache.keys);assertEquals(command,m.state.value.cache.outbox.single().command)
        assertEquals("192.168.1.10:51831",m.state.value.cache.config!!.text("endpoint"));assertEquals("token",m.state.value.cache.token)
    }

}
