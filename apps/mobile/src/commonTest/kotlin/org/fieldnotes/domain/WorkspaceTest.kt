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
    var configureFailure = false
    var configurations = 0
    var stopped = false
    val requests = mutableListOf<String>()
    var directoryAvailable = true
    var networkFailure = false
    var rejection: Int? = null
    var expired = false
    var compatibility:JsonObject?=null
    var additiveFields=false
    var sent = mutableListOf<Command>()
    var results = mutableMapOf<String, JsonObject>()
    var recoveryReply:((JsonObject)->Reply)?=null
    var beforeExchange:(suspend (String)->Unit)?=null
    var loseReply = false
    override suspend fun keys() = obj("private_key" to "private-test-key".j())
    override suspend fun configure(configuration: JsonObject) { configurations++;if(configureFailure)error("DNS unavailable") }
    override suspend fun stop() { stopped=true }
    override suspend fun exchange(path: String, token: String?, body: JsonObject?, serverKey: String?): Reply {
        beforeExchange?.invoke(path)
        requests+=path
        if (networkFailure) error("Offline")
        if(path=="/api/recovery")return checkNotNull(recoveryReply){"Missing recovery fixture"}(checkNotNull(body))
        if (path == "/api/login") return Reply(200, obj("token" to "new-token".j(), "actor" to WireJson.encodeToJsonElement(actor)).toString())
        if (expired) return Reply(401, obj("error" to "Session expired".j()).toString())
        if(path.startsWith("/api/device/update"))return Reply(200,"""{"available":false}""")
        if(path.startsWith("/api/transfer/"))return Reply(200,"{}")
        if (path == "/api/device/revoke-self") return Reply(200,"""{"authorization_revoked":true}""")
        if (path == "/api/people" && !directoryAvailable) return Reply(404, """{"error":"Not available"}""")
        if (path == "/api/people") return Reply(200,obj("people" to WireJson.encodeToJsonElement(listOf(HouseholdPerson(actor.user_id,actor.name,true)))).toString())
        if (path == "/api/head") return Reply(200,JsonObject(obj("epoch" to snapshot.epoch.j(),"generation" to snapshot.generation.j(),"sequence" to snapshot.sequence.j())+(compatibility?.let { mapOf("compatibility" to it) } ?: emptyMap())).toString())
        if (path == "/api/snapshot") return Reply(200, JsonObject(WireJson.encodeToJsonElement(snapshot).jsonObject+(if(additiveFields)mapOf("future_metadata" to obj("enabled" to JsonPrimitive(true))) else emptyMap())).toString())
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
    @Test fun queuedListUnbundleProjectsReleasedTasksWithoutChangingTheSavedSnapshot() {
        val list=Record("list","list",1,obj("title" to "Build".j(),"items_are_tasks" to JsonPrimitive(true)))
        val direct=Record("direct","task",1,obj("title" to "Measure".j(),"project_id" to list.id.j()))
        val comment=Record("comment","comment",1,obj("task_id" to direct.id.j(),"body" to "Keep".j()))
        val snapshot=initial().snapshot!!.copy(records=listOf(list,direct,comment))
        val command=Command("op","epoch",1,"list.unbundle",list.id,1,obj("contents" to JsonArray(emptyList())))
        val cache=initial().copy(snapshot=snapshot,outbox=listOf(Pending(command)))
        val visible=projectRecords(cache).associateBy{it.record.id}
        assertEquals("",visible.getValue(direct.id).record.data.text("project_id"))
        assertEquals(list.id,snapshot.records.first{it.id==direct.id}.data.text("project_id"))
        assertEquals("Keep",visible.getValue(comment.id).record.data.text("body"))
        assertTrue(visible.getValue(direct.id).pending)
        assertEquals(list.id,visible.getValue(list.id).record.id)
    }

    @Test fun projectMovesPersistOnlyMembershipAndSurviveOfflineRestart()=runTest {
        val a=Record("a","project",1,obj("title" to "Kitchen".j()))
        val b=a.copy(id="b")
        val v=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(records=listOf(task,a,b))))
        val t=FakeTransport(v.value.snapshot!!);val m=model(v,t,backgroundScope);m.initialize()
        val basis=v.value.snapshot!!.basis
        m.moveTask(task.id,a.id,1,basis)
        assertEquals(obj("project_id" to a.id.j()),v.value.outbox.single().command.data)
        var next=0
        val restarted=Workspace(t,v,{"restarted-${next++}"},backgroundScope);restarted.initialize()
        assertEquals(a.id,projectRecords(restarted.state.value.cache).first {it.record.id==task.id}.record.data.text("project_id"))
        restarted.sync()
        restarted.moveTask(task.id,b.id,2,basis);restarted.sync()
        restarted.moveTask(task.id,"",3,basis);restarted.sync()
        val result=v.value.snapshot!!.records.first {it.id==task.id}
        assertEquals("",result.data.text("project_id"));assertEquals(task.data.text("notes"),result.data.text("notes"));assertEquals(task.data.text("due"),result.data.text("due"))
        assertEquals(4L,result.revision)
    }
    @Test fun projectMovesKeepDraftsAndRefuseStaleOrInvalidMoves()=runTest {
        val project=Record("project","project",1,obj("title" to "Kitchen".j()))
        val v=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(records=listOf(task,project))))
        val m=model(v,FakeTransport(v.value.snapshot!!),backgroundScope);m.initialize();val basis=v.value.snapshot!!.basis
        m.saveDraft(task.id,1,obj("notes" to "Unsaved measurements".j()))
        assertFails {m.moveTask(task.id,project.id,1,basis)}
        assertEquals("Unsaved measurements",v.value.drafts[task.id]!!.data.text("notes"));assertTrue(v.value.outbox.isEmpty())
        m.discardDraft(task.id)
        assertFails {m.moveTask(task.id,project.id,0,basis)}
        assertFails {m.moveTask(task.id,"missing",1,basis)}
        assertFails {m.moveTask(task.id,project.id,1,Basis("restored",1))}
        v.failing=true;assertFails {m.moveTask(task.id,project.id,1,basis)};assertTrue(m.state.value.cache.outbox.isEmpty());v.failing=false
        m.enqueue("task.update",task.id,obj("notes" to "Queued measurements".j()),1)
        assertFails {m.moveTask(task.id,project.id,1,basis)}
        assertEquals(1,v.value.outbox.size)
    }

    @Test fun newerReminderTimingIsRetainedIntactWhenTheServerIsOlder() = runTest {
        val vault=MemoryVault();val transport=FakeTransport();val workspace=model(vault,transport,this);workspace.initialize()
        val rules=ReminderSet(listOf(ReminderAlert("annual",0,lead_time=ReminderLead(1,"years","UTC"))),timing_version=1)
        workspace.enqueue("task.update",task.id,obj("title" to "Preserve this too".j(),"reminders" to reminderData(rules)),task.revision)
        workspace.sync()
        assertTrue(transport.sent.isEmpty());val retained=vault.value.conflicts.single()
        assertTrue(retained.reason.contains("custom reminder timing"))
        assertEquals(reminderData(rules),retained.command.data["reminders"])
        assertEquals("Preserve this too",retained.command.data.text("title"))
        assertEquals(initial().keys,vault.value.keys)
    }
    @Test fun unsupportedItemCategoriesStayInRetainedChangesWithTheirCompletePayload() = runTest {
        val vault=MemoryVault();val transport=FakeTransport()
        val workspace=model(vault,transport,this)
        workspace.initialize()
        workspace.enqueue("task.update",task.id,obj("title" to "Keep this full edit".j(),"item_category" to "Anything custom".j()),task.revision)
        workspace.sync()
        assertTrue(transport.sent.isEmpty())
        val retained=vault.value.conflicts.single()
        assertTrue(retained.reason.contains("item category"))
        assertEquals("Keep this full edit",retained.command.data.text("title"))
        assertEquals("Anything custom",retained.command.data.text("item_category"))
        assertEquals(initial().keys,vault.value.keys)
        assertEquals(initial().config,vault.value.config)
        val restarted=model(vault,FakeTransport(),this);restarted.initialize()
        assertEquals(retained,restarted.state.value.cache.conflicts.single())
        assertTrue(portableManifest(restarted.state.value.cache).getValue("retained").jsonArray.toString().contains("Anything custom"))
    }
    @Test fun visibleAppChecksContinuouslyEvenWithLegacyAutoRefreshDisabled()=runTest {
        val vault=MemoryVault(initial().copy(preferences=Preferences(autoRefresh=false,liveUpdates=false)))
        val transport=FakeTransport();val workspace=model(vault,transport,backgroundScope);workspace.initialize()
        val follow=backgroundScope.launch{workspace.followForegroundUpdates()};runCurrent()
        val initialHeads=transport.requests.count{it=="/api/head"}
        transport.snapshot=transport.snapshot.copy(sequence=2,records=listOf(task.copy(revision=2,data=JsonObject(task.data+("notes" to "Added from the web at the store".j())))))
        advanceTimeBy(FOREGROUND_REFRESH_MILLIS-1);runCurrent();assertEquals(initialHeads,transport.requests.count{it=="/api/head"})
        advanceTimeBy(1);runCurrent();assertEquals("Added from the web at the store",vault.value.snapshot!!.records.single().data.text("notes"))
        assertFalse(workspace.state.value.refreshPending)
        transport.networkFailure=true;advanceTimeBy(FOREGROUND_REFRESH_MILLIS);runCurrent();assertFalse(workspace.state.value.online)
        transport.networkFailure=false;advanceTimeBy(FOREGROUND_REFRESH_MILLIS);runCurrent();assertTrue(workspace.state.value.online)
        follow.cancel();val stopped=transport.requests.size;advanceTimeBy(30_000);runCurrent();assertEquals(stopped,transport.requests.size)
    }
    @Test fun visibleRefreshWaitsForAnOverlappingRequestAndDownloadsBeforeReportingCurrent()=runTest {
        val vault=MemoryVault();val transport=FakeTransport();val workspace=model(vault,transport,backgroundScope);workspace.initialize()
        val held=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var first=true
        transport.beforeExchange={path->if(path=="/api/head"&&first){first=false;held.complete(Unit);release.await()}}
        workspace.launch{workspace.poll()};runCurrent();assertTrue(held.isCompleted)
        transport.snapshot=transport.snapshot.copy(sequence=2,records=listOf(task.copy(revision=2,data=JsonObject(task.data+("notes" to "New groceries".j())))))
        val refresh=backgroundScope.launch{workspace.refreshWhileVisible()};runCurrent();assertTrue(refresh.isActive)
        release.complete(Unit);runCurrent();assertEquals("New groceries",workspace.state.value.cache.snapshot!!.records.single().data.text("notes"));assertFalse(workspace.state.value.refreshPending)
    }
    @Test fun networkHandoffReopensOnlyTheTransportAndKeepsIdentityAndQueuedCommands()=runTest {
        val vault=MemoryVault();val transport=FakeTransport();val workspace=model(vault,transport,backgroundScope);workspace.initialize()
        val keys=vault.value.keys;val binding=vault.value.config
        workspace.enqueue("task.update",task.id,obj("notes" to "Keep this during handoff".j()),1)
        val pending=vault.value.outbox;transport.networkFailure=true;workspace.networkChanged()
        assertEquals(pending,vault.value.outbox);assertEquals(keys,vault.value.keys);assertEquals(binding,vault.value.config);assertTrue(transport.stopped)
        transport.networkFailure=false;workspace.networkChanged();assertTrue(vault.value.outbox.isEmpty());assertTrue(transport.configurations>=3)
        assertEquals(keys,vault.value.keys);assertEquals(binding,vault.value.config)
        workspace.pauseConnection();val requests=transport.requests.size;workspace.networkChanged();assertEquals(requests,transport.requests.size);assertTrue(workspace.state.value.connectionPaused)
    }
    @Test fun networkHandoffCanDeferAllTrafficUntilTheAppReturnsToTheForeground()=runTest {
        val vault=MemoryVault();val transport=FakeTransport();val workspace=model(vault,transport,backgroundScope);workspace.initialize()
        val requests=transport.requests.size;workspace.networkChanged(refresh=false)
        assertEquals(requests,transport.requests.size);assertTrue(transport.stopped)
        workspace.refreshWhileVisible();assertTrue(transport.requests.size>requests);assertTrue(workspace.state.value.online)
    }
    @Test fun removingAnUncheckedListItemIsDurableAndDoesNotLeaveItsEditorDraftBehind()=runTest {
        val list=Record("list","list",1,obj("title" to "Food".j(),"template" to "groceries".j()))
        val item=task.copy(data=JsonObject(task.data+("project_id" to list.id.j())))
        val image=Record("image","image",1,obj("task_id" to item.id.j()))
        val vault=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(records=listOf(list,item,image))))
        val workspace=model(vault,FakeTransport(vault.value.snapshot!!),backgroundScope);workspace.initialize();workspace.saveDraft(item.id,1,obj("title" to "Unsent spelling correction".j()))
        vault.failing=true;assertFails{workspace.removeTask(item,vault.value.snapshot!!.basis)};assertTrue(workspace.state.value.cache.drafts.containsKey(item.id));assertTrue(workspace.state.value.cache.outbox.isEmpty())
        vault.failing=false;workspace.enqueue("task.update",item.id,obj("quantity" to "4".j()),1)
        workspace.removeTask(item,vault.value.snapshot!!.basis)
        assertEquals(listOf("task.update","task.delete"),vault.value.outbox.map{it.command.action});assertEquals(vault.value.outbox.first().command.operation_id,vault.value.outbox.last().dependsOn)
        assertFalse(vault.value.drafts.containsKey(item.id));assertEquals(listOf(list.id),projectRecords(vault.value).map{it.record.id})
        val reopened=model(vault,FakeTransport(vault.value.snapshot!!),backgroundScope);reopened.initialize();assertEquals(vault.value.outbox,reopened.state.value.cache.outbox)
        assertFails{reopened.removeTask(item,Basis("old-workspace",1))}
    }
    @Test fun savedEditSyncWaitsForAnOverlappingPollInsteadOfDroppingTheRequest()=runTest {
        val vault=MemoryVault();val transport=FakeTransport();val workspace=model(vault,transport,backgroundScope);workspace.initialize()
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var first=true
        transport.beforeExchange={path->if(path=="/api/head"&&first){first=false;started.complete(Unit);release.await()}}
        workspace.launch{workspace.poll()};runCurrent();assertTrue(started.isCompleted)
        workspace.enqueue("task.update",task.id,obj("notes" to "Saved during poll".j()),task.revision)
        workspace.requestSync();runCurrent();assertTrue(transport.sent.isEmpty())
        release.complete(Unit);runCurrent()
        assertEquals(1,transport.sent.size);assertTrue(workspace.state.value.cache.outbox.isEmpty());assertEquals("Saved during poll",vault.value.snapshot!!.records.single().data.text("notes"))
    }
    @Test fun savedEditSyncSurvivesEditorCancellationWithoutAutoRefresh()=runTest {
        val vault=MemoryVault();val transport=FakeTransport();val workspace=model(vault,transport,backgroundScope);workspace.initialize()
        val editor=Job();val editorScope=CoroutineScope(editor+StandardTestDispatcher(testScheduler))
        editorScope.launch {
            workspace.enqueue("task.update",task.id,obj("notes" to "Saved before closing".j()),task.revision)
            workspace.requestSync()
            editor.cancel()
        }
        runCurrent()
        assertTrue(editor.isCancelled);assertEquals(1,transport.sent.size);assertTrue(workspace.state.value.cache.outbox.isEmpty())
        assertEquals("Saved before closing",vault.value.snapshot!!.records.single().data.text("notes"))
        val reopened=model(vault,transport,backgroundScope);reopened.initialize();assertEquals("Saved before closing",reopened.state.value.cache.snapshot!!.records.single().data.text("notes"))
    }
    @Test fun offlineProjectListTaskChainKeepsParentDependenciesAndChildRevisions()=runTest {
        val feature=obj("server_version" to "0.9.0".j(),"protocol_min" to 1.j(),"protocol_max" to 1.j(),"minimum_client_code" to 10.j(),"recommended_client_code" to CURRENT_CLIENT_CODE.j(),"features" to JsonArray(listOf(PROJECTS_FEATURE.j())))
        val vault=MemoryVault();val transport=FakeTransport().also{it.compatibility=feature};val workspace=model(vault,transport,backgroundScope);workspace.initialize();workspace.poll()
        workspace.enqueue("project.create","project",obj("title" to "Weekend".j()),null)
        workspace.enqueue("list.create","list",obj("title" to "Honey-do".j(),"project_id" to "project".j(),"items_are_tasks" to JsonPrimitive(true)),null)
        workspace.enqueue("task.create","child",obj("title" to "Fix hinge".j(),"project_id" to "list".j()),null)
        val queued=vault.value.outbox;assertEquals(queued[0].command.operation_id,queued[1].parentCreation);assertEquals(queued[1].command.operation_id,queued[2].parentCreation)
        val restarted=model(vault,transport,backgroundScope);restarted.initialize();restarted.sync()
        assertTrue(vault.value.outbox.isEmpty());assertTrue(vault.value.conflicts.isEmpty())
        assertEquals(listOf("project.create","list.create","task.create"),transport.sent.map{it.action});assertTrue(transport.sent.all{it.expected_revision==null})
        assertEquals("list",vault.value.snapshot!!.records.first{it.id=="child"}.data.text("project_id"))
    }
    @Test fun failedProjectCreationRetainsItsWholeDependentGroupWithoutSendingOrphanRequests()=runTest {
        val vault=MemoryVault();val transport=FakeTransport();val workspace=model(vault,transport,backgroundScope);workspace.initialize()
        workspace.enqueue("project.create","project",obj("title" to "Weekend".j()),null)
        workspace.enqueue("list.create","list",obj("title" to "Group".j(),"project_id" to "project".j()),null)
        workspace.enqueue("task.create","child",obj("title" to "Fix hinge".j(),"project_id" to "list".j()),null)
        transport.rejection=409;workspace.sync();assertEquals(listOf("project.create"),transport.sent.map{it.action})
        assertEquals(3,vault.value.conflicts.size);assertTrue(vault.value.outbox.isEmpty());assertEquals("Fix hinge",vault.value.conflicts.last().command.data.text("title"))
    }

    @Test fun recoveryIntentIsDurableBeforeSendingAndLostRepliesCanBeConfirmedAfterRestart()=runTest {
        val v=MemoryVault(initial().copy(compatibility=ServerCompatibility(features=setOf(RECOVERY_FEATURE))));val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        val preview=RecoveryPreview("preview",9999999999,"epoch",1,"version",1,"Saved state",false,change_count=1,changes=emptyList(),confirmation_phrase="RESTORE ITEM #1")
        // Use the real fixture basis rather than inventing a second workspace.
        val plan=preview.copy(epoch=v.value.snapshot!!.epoch,generation=v.value.snapshot!!.generation)
        assertFailsWith<IllegalArgumentException>{m.recovery("apply")}
        val saved=v.value;v.failing=true
        assertFailsWith<IllegalStateException>{m.applyRecovery(plan,"RESTORE ITEM #1",true)};assertTrue(t.requests.isEmpty());v.failing=false
        var applied=0;var reply:JsonObject?=null
        t.recoveryReply={body->
            when(body.text("action")){
                "apply"->{assertNotNull(v.value.recoveryPending);applied++;t.snapshot=t.snapshot.copy(generation=2);reply=obj("ok" to JsonPrimitive(true),"changed" to 1.j());error("Reply lost after commit")}
                "result"->{assertEquals(v.value.recoveryPending!!.request.text("operation_id"),body.text("operation_id"));Reply(200,obj("known" to JsonPrimitive(true),"result" to checkNotNull(reply)).toString())}
                else->error("Unexpected recovery request")
            }
        }
        assertFailsWith<IllegalStateException>{m.applyRecovery(plan,"RESTORE ITEM #1",true)};assertNotNull(v.value.recoveryPending);assertEquals(1,applied)
        val restarted=model(v,t,backgroundScope);restarted.initialize();assertNotNull(restarted.state.value.cache.recoveryPending)
        assertFailsWith<IllegalStateException>{restarted.resetConnection()};assertFailsWith<IllegalStateException>{restarted.logout()};assertFailsWith<IllegalStateException>{restarted.removeInstallation()}
        assertNotNull(restarted.checkRecoveryResult());assertNull(v.value.recoveryPending);assertEquals(2,v.value.snapshot!!.generation);assertEquals(saved.keys,v.value.keys);assertEquals(saved.config,v.value.config);assertEquals(saved.token,v.value.token);assertEquals(1,applied)
    }
    @Test fun checkpointDeletionRetainsItsOriginalBasisAndDurableRequestAfterALostReply()=runTest {
        val v=MemoryVault(initial().copy(compatibility=ServerCompatibility(features=setOf(RECOVERY_FEATURE))));val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        val snap=v.value.snapshot!!;val entry=RecoveryVersion("checkpoint",4,at=123,actor_name="Owner",action="checkpoint.create",title="Old copy",can_delete=true)
        val page=RecoveryPage(snap.epoch,snap.generation,"lineage",listOf(entry),false,4,17)
        assertFailsWith<IllegalStateException>{m.deleteCheckpoint(entry,page.copy(generation=page.generation+1),"DELETE CHECKPOINT #4")}
        assertTrue(t.requests.isEmpty());assertNull(v.value.recoveryPending)
        var sent:JsonObject?=null
        t.recoveryReply={body->sent=body;assertNotNull(v.value.recoveryPending);error("Delete reply lost")}
        assertFailsWith<IllegalStateException>{m.deleteCheckpoint(entry,page,"DELETE CHECKPOINT #4")}
        assertEquals("checkpoint.delete",sent!!.text("action"));assertEquals(17,sent!!.number("history_revision"));assertEquals(sent,v.value.recoveryPending!!.request)
        val restarted=model(v,t,backgroundScope);restarted.initialize()
        t.recoveryReply={body->assertEquals(sent,body);Reply(200,obj("ok" to JsonPrimitive(true)).toString())}
        restarted.retryRecovery();assertNull(v.value.recoveryPending)
    }
    @Test fun aCommittedRestoreKeepsItsPendingReceiptIfSavingTheConfirmationFails()=runTest {
        val v=MemoryVault(initial().copy(compatibility=ServerCompatibility(features=setOf(RECOVERY_FEATURE))));val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        val basis=v.value.snapshot!!.basis;val plan=RecoveryPreview("preview",9999999999,basis.epoch,basis.generation,"version",1,"Saved state",false,change_count=1,changes=emptyList(),confirmation_phrase="RESTORE ITEM #1")
        t.recoveryReply={v.failing=true;Reply(200,obj("ok" to JsonPrimitive(true)).toString())}
        val failure=assertFailsWith<IllegalStateException>{m.applyRecovery(plan,"RESTORE ITEM #1",true)}
        assertContains(failure.message.orEmpty(),"confirmed recovery");assertNotNull(v.value.recoveryPending)
        v.failing=false;t.recoveryReply={Reply(200,obj("known" to JsonPrimitive(true),"result" to obj("ok" to JsonPrimitive(true))).toString())}
        assertNotNull(m.checkRecoveryResult());assertNull(v.value.recoveryPending)
    }
    @Test fun newCommentRetainsTheParentRestoreIdentityInsteadOfAdoptingALaterOne()=runTest {
        val current=task.copy(data=JsonObject(task.data+("recovery_incarnation" to 3.j())))
        val v=MemoryVault(initial().copy(compatibility=ServerCompatibility(features=setOf(RECOVERY_FEATURE)),snapshot=initial().snapshot!!.copy(records=listOf(current))))
        val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();m.enqueue("comment.create","comment",obj("task_id" to task.id.j(),"body" to "Retained comment".j()),null)
        assertEquals(3,v.value.outbox.single().command.data.number("parent_incarnation"))
        val restored=model(v,t,backgroundScope);restored.initialize();assertEquals(3,restored.state.value.cache.outbox.single().command.data.number("parent_incarnation"))
        t.compatibility=obj("server_version" to "0.8.0".j(),"protocol_min" to 1.j(),"protocol_max" to 1.j(),"minimum_client_code" to 10.j(),"recommended_client_code" to CURRENT_CLIENT_CODE.j(),"features" to JsonArray(listOf(RECOVERY_FEATURE.j())))
        t.snapshot=t.snapshot.copy(records=listOf(current.copy(data=JsonObject(current.data+("recovery_incarnation" to 4.j())))))
        t.rejection=409;restored.sync();val retained=v.value.conflicts.single();assertEquals(3,retained.command.data.number("parent_incarnation"))
        restored.resolve(retained.command.operation_id,true);assertEquals(4,v.value.outbox.single().command.data.number("parent_incarnation"));assertEquals("Retained comment",v.value.outbox.single().command.data.text("body"))

    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aWaitingImportCannotSendItsFileAfterTheAccountOrWorkspaceChanges()=runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        val original=v.value;val basis=original.snapshot!!.basis;val user=actor.user_id
        assertEquals(obj(),m.transfer("prepare",obj(),basis,user));t.requests.clear()
        val held=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val exporting=async {m.portableSnapshot {held.complete(Unit);release.await()}}
        held.await()
        v.value=original.copy(snapshot=original.snapshot!!.copy(actor=actor.copy(user_id="other-person")))
        val signIn=async {m.initialize()};runCurrent()
        val importing=async {runCatching {m.transfer("prepare",obj("manifest" to "Private archive".j()),basis,user)}}
        runCurrent();release.complete(Unit);exporting.await();signIn.await()
        assertIs<IllegalStateException>(importing.await().exceptionOrNull());assertTrue(t.requests.isEmpty())
        v.value=original.copy(snapshot=original.snapshot!!.copy(generation=2));m.initialize()
        assertFailsWith<IllegalStateException>{m.transfer("prepare",obj(),basis,user)}
        assertTrue(t.requests.isEmpty());assertEquals(original.keys,v.value.keys)
        assertEquals(original.outbox,v.value.outbox)
    }
    @Test fun compatibleDriftAcceptsAdditiveDataAndIncompatibleServerRetainsTheOutbox()=runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        t.additiveFields=true
        t.compatibility=obj("server_version" to "0.9.0".j(),"protocol_min" to 1.j(),"protocol_max" to 1.j(),"minimum_client_code" to 5.j(),"recommended_client_code" to (CURRENT_CLIENT_CODE+1).j(),"features" to JsonArray(listOf(REMINDER_OPTIONS_FEATURE.j())))
        m.sync();assertTrue(m.state.value.online);assertTrue(v.value.supportsRemindersV2());assertContains(v.value.compatibility!!.message,"recommended")
        m.enqueue("task.update",task.id,obj("notes" to "Retained through drift".j()),1)
        val pending=v.value.outbox;val keys=v.value.keys
        m.observeCompatibility(obj("compatibility" to JsonObject(t.compatibility!!+mapOf("minimum_client_code" to (CURRENT_CLIENT_CODE+2).j(),"recommended_client_code" to (CURRENT_CLIENT_CODE+2).j()))))
        m.sync();assertTrue(t.sent.isEmpty());assertEquals(pending,v.value.outbox);assertEquals(keys,v.value.keys);assertNotNull(m.state.value.problem)
        val restored=model(v,t,backgroundScope);restored.initialize();assertEquals(pending,restored.state.value.cache.outbox);assertNotNull(restored.state.value.problem)
    }
    @Test fun newReminderEditsOnAnOlderServerAreRetainedWithAnExplanation()=runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        m.enqueue("task.update",task.id,obj("reminder_options" to reminderOptionsData(ReminderOptions())),1)
        m.sync();assertTrue(m.state.value.online);assertTrue(t.sent.isEmpty());assertTrue(v.value.outbox.isEmpty());assertEquals(1,v.value.conflicts.size);assertContains(v.value.conflicts.single().reason,"newer server")
    }
    @Test fun clearingCheckedIsDurableAtomicAndDependsOnTheOfflineCompletion()=runTest {
        val list=Record("groceries","list",1,obj("title" to "Groceries".j()))
        val item=task.copy(data=JsonObject(task.data+mapOf("project_id" to list.id.j())))
        val other=item.copy(id="leave-unchecked")
        val v=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(records=listOf(list,item,other))))
        val m=model(v,FakeTransport(v.value.snapshot!!),backgroundScope);m.initialize()
        m.enqueue("task.complete",item.id,obj(),1)
        v.failing=true
        assertFails { m.clearChecked(list.id) }
        assertEquals(1,m.state.value.cache.outbox.size)
        v.failing=false;m.clearChecked(list.id)
        val queued=v.value.outbox
        assertEquals(listOf("task.complete","task.delete"),queued.map { it.command.action })
        assertEquals(queued.first().command.operation_id,queued.last().dependsOn)
        val restarted=model(v,FakeTransport(v.value.snapshot!!),backgroundScope);restarted.initialize()
        assertEquals(queued,restarted.state.value.cache.outbox)
        assertEquals(listOf(other.id),projectRecords(restarted.state.value.cache).filter { it.record.kind=="task" }.map { it.record.id })
    }
    @Test fun reusingCheckedItemsIsDurableAndKeepsContentAndRevisionDependencies()=runTest {
        for(workTasks in listOf(false,true)) {
        val list=Record("groceries","list",1,obj("title" to "Rolling list".j(),"perpetual" to JsonPrimitive(true),"items_are_tasks" to JsonPrimitive(workTasks)))
        val item=task.copy(data=JsonObject(task.data+mapOf("project_id" to list.id.j(),"quantity" to "2".j(),"store" to "Costco".j())))
        val image=Record("image","image",1,obj("task_id" to item.id.j(),"name" to "Attached photo".j()))
        val other=item.copy(id="leave-unchecked")
        val v=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(records=listOf(list,item,image,other))))
        val m=model(v,FakeTransport(v.value.snapshot!!),backgroundScope);m.initialize()
        m.enqueue("task.complete",item.id,obj(),1)
        val completed=projectRecords(m.state.value.cache).single { it.record.id==item.id }.record
        v.failing=true;assertFails { m.reuseCheckedItems(listOf(completed),v.value.snapshot!!.basis) };assertEquals(1,m.state.value.cache.outbox.size)
        v.failing=false;m.reuseCheckedItems(listOf(completed),v.value.snapshot!!.basis)
        assertEquals(listOf("task.complete","task.reopen"),v.value.outbox.map { it.command.action })
        assertEquals(v.value.outbox.first().command.operation_id,v.value.outbox.last().dependsOn)
        val restarted=model(v,FakeTransport(v.value.snapshot!!),backgroundScope);restarted.initialize()
        val records=projectRecords(restarted.state.value.cache).map { it.record }
        val reopened=records.single { it.id==item.id }
        assertEquals("todo",reopened.data.text("status"));assertEquals("Measure first",reopened.data.text("notes"));assertEquals("2",reopened.data.text("quantity"));assertEquals("Costco",reopened.data.text("store"))
        assertTrue(records.any { it.id==image.id });assertTrue(records.any { it.id==other.id })
        assertFails { restarted.reuseCheckedItems(listOf(completed),Basis("different",1)) }
        }
    }
    @Test fun ddilRestartMergesDifferentItemsIntoARemotelyChangedListAndRetainsSameItemConflicts()=runTest {
        val list=Record("groceries","list",1,obj("title" to "Groceries".j()))
        val first=task.copy(id="milk",data=JsonObject(task.data+mapOf("project_id" to list.id.j(),"title" to "Milk".j())))
        val second=task.copy(id="bread",data=JsonObject(task.data+mapOf("project_id" to list.id.j(),"title" to "Bread".j())))
        val v=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(records=listOf(list,first,second))))
        val keys=v.value.keys;val t=FakeTransport(v.value.snapshot!!);var nextOperation=0
        fun restored()=Workspace(t,v,{"ddil-operation-${nextOperation++}"},backgroundScope)
        var m=restored();m.initialize()
        t.networkFailure=true
        m.enqueue("task.update",first.id,obj("notes" to "Phone offline: oat milk".j()),1)
        m.enqueue("task.create","eggs",obj("project_id" to list.id.j(),"title" to "Eggs".j()),null)
        m.sync();assertEquals(2,v.value.outbox.size)
        t.snapshot=t.snapshot.copy(records=listOf(list.copy(revision=2,data=obj("title" to "Weekly groceries".j())),first,second.copy(revision=2,data=JsonObject(second.data+("title" to "Wholemeal bread".j())))))
        m=restored();m.initialize();assertEquals(2,v.value.outbox.size)
        t.networkFailure=false;m.sync()
        assertTrue(v.value.outbox.isEmpty());assertTrue(v.value.conflicts.isEmpty());assertEquals(keys,v.value.keys)
        val merged=v.value.snapshot!!.records.associateBy { it.id }
        assertEquals("Weekly groceries",merged.getValue(list.id).data.text("title"));assertEquals("Wholemeal bread",merged.getValue(second.id).data.text("title"))
        assertEquals("Phone offline: oat milk",merged.getValue(first.id).data.text("notes"));assertEquals(list.id,merged.getValue("eggs").data.text("project_id"))
        t.networkFailure=true;m.enqueue("task.update",first.id,obj("notes" to "Second offline edit".j()),merged.getValue(first.id).revision);m.sync()
        t.snapshot=t.snapshot.copy(records=t.snapshot.records.map { if(it.id==first.id)it.copy(revision=it.revision+1,data=JsonObject(it.data+("notes" to "Partner edit while absent".j()))) else it })
        m=restored();m.initialize();t.networkFailure=false;m.sync()
        assertEquals(1,v.value.conflicts.size);assertEquals("Second offline edit",v.value.conflicts.single().command.data.text("notes"))
        assertEquals("Partner edit while absent",v.value.snapshot!!.records.single { it.id==first.id }.data.text("notes"));assertEquals(keys,v.value.keys)
    }
    @Test fun softwareUpdatesSharePauseRevocationAndRouteFencesWithoutTouchingQueuedEdits()=runTest{
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        m.enqueue("task.update","task",obj("notes" to "Keep during APK update".j()),1)
        val keys=v.value.keys;val pending=v.value.outbox
        assertEquals(200,m.softwareUpdate("/api/device/update").status)
        for(path in listOf("/api/admin","https://elsewhere.example/apk","/api/device/update/chunks/../0"))assertFailsWith<IllegalArgumentException>{m.softwareUpdate(path)}
        assertEquals(listOf("/api/device/update"),t.requests)
        m.pauseConnection();assertFailsWith<IllegalStateException>{m.softwareUpdate("/api/device/update")}
        m.resumeConnection();t.expired=true;assertEquals(401,m.softwareUpdate("/api/device/update").status)
        assertNull(v.value.token);assertTrue(m.state.value.needsLogin);assertEquals(keys,v.value.keys);assertEquals(pending,v.value.outbox)
    }
    @Test fun missingIdentityPreservesExistingRecordsInsteadOfGeneratingReplacementKeys()=runTest{
        val original=initial().copy(keys=null);val v=MemoryVault(original);val t=FakeTransport();val m=model(v,t,backgroundScope)
        m.initialize();assertFalse(m.state.value.ready);assertEquals(original,v.value);assertEquals(0,t.configurations)
        assertTrue(m.state.value.error!!.contains("identity"))
    }
    @Test fun pausedConnectionKeepsOfflineWorkAndRemindersAndNeedsExplicitResumeAfterRestart()=runTest{
        val v=MemoryVault();val t=FakeTransport();var m=model(v,t,backgroundScope);m.initialize()
        m.pauseConnection();m.saveDraft(task.id,task.revision,obj("notes" to "Kept while paused".j()));m.submitDraft(task.id)
        assertFalse(m.poll());assertTrue(t.requests.isEmpty());assertEquals(1,v.value.outbox.size);assertTrue(v.value.preferences.reminders)
        m=model(v,t,backgroundScope);m.initialize();assertEquals(1,t.configurations);assertTrue(m.state.value.connectionPaused)
        m.resumeConnection();m.sync();assertTrue(v.value.outbox.isEmpty());assertEquals("Kept while paused",v.value.snapshot!!.records.single().data.text("notes"));assertEquals(2,t.configurations)
    }
    @Test fun aFailedTransportIsReconfiguredBeforeRetryingSoTrustedDdnsCanBeResolvedAgain()=runTest{
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        t.networkFailure=true;m.sync();assertTrue(t.stopped);assertEquals(1,t.configurations)
        t.networkFailure=false;m.sync();assertTrue(m.state.value.online);assertEquals(2,t.configurations)
    }
    @Test fun assignmentDraftAndDirectorySurviveOfflineRestartAndOlderServerStillSyncs() = runTest {
        val original=initial().copy(people=listOf(HouseholdPerson(actor.user_id,actor.name,true)))
        val v=MemoryVault(original);val t=FakeTransport();var m=model(v,t,backgroundScope);m.initialize()
        t.networkFailure=true;m.saveDraft(task.id,task.revision,obj("assignee" to actor.user_id.j()));m.submitDraft(task.id);m.sync()
        m=model(v,t,backgroundScope);m.initialize();assertEquals(actor.name,m.state.value.cache.people.single().name)
        assertEquals(actor.user_id,projectRecords(m.state.value.cache).single().record.data.text("assignee"))
        t.networkFailure=false;m.sync();assertTrue(m.state.value.cache.outbox.isEmpty());assertEquals(actor.user_id,t.sent.single().data.text("assignee"))
        t.directoryAvailable=false;m.sync();assertTrue(m.state.value.online);assertTrue(m.state.value.cache.people.isEmpty())
    }
    @Test fun reenrollmentCannotOverwriteOfflineChanges() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();m.enqueue("task.update","task",obj("notes" to "Retain me".j()),1)
        assertFailsWith<IllegalStateException> { m.resetConnection() };assertNotNull(v.value.config);assertEquals(1,v.value.outbox.size)
        m.discardRetainedChanges();m.resetConnection();assertNull(v.value.config);assertNull(v.value.snapshot);assertNull(v.value.token)
    }
    @Test fun standaloneNoteDraftsRemainNotesAcrossOfflineRestartAndAcknowledgement() = runTest {
        val n=Record("reference","note",1,obj("title" to "Household reference".j(),"body" to "Before".j()))
        val v=MemoryVault(initial().copy(snapshot=initial().snapshot!!.copy(records=listOf(n))));val t=FakeTransport(v.value.snapshot!!);var m=model(v,t,backgroundScope);m.initialize()
        m.saveDraft(n.id,1,obj("body" to "Offline reference".j()),action="note.update");m.submitDraft(n.id);t.networkFailure=true;m.sync();assertEquals("Offline reference",projectRecords(m.state.value.cache).single().record.data.text("body"))
        m=model(v,t,backgroundScope);m.initialize();t.networkFailure=false;m.sync();assertEquals("note.update",t.sent.single().action);assertEquals("note",v.value.snapshot!!.records.single().kind);assertEquals("Offline reference",v.value.snapshot!!.records.single().data.text("body"))
    }
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
        assertTrue(m.state.value.needsLogin);assertNull(v.value.token);assertEquals(1,m.state.value.cache.outbox.size);assertTrue(m.state.value.cache.conflicts.isEmpty())
        val restarted=model(v,t,backgroundScope);restarted.initialize();assertTrue(restarted.state.value.needsLogin);assertEquals(1,restarted.state.value.cache.outbox.size)
    }
    @Test fun rejectedSessionFailsClosedEvenWhenCredentialRemovalCannotPersist() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize();m.enqueue("task.update","task",obj("notes" to "Retain".j()),1)
        v.failing=true;t.expired=true;m.sync()
        assertTrue(m.state.value.needsLogin);assertEquals(1,m.state.value.cache.outbox.size)
        val opted=m.state.value.cache.copy(preferences=Preferences(widgetTitles=true))
        assertFalse(widgetMayShowTitles(opted,true,!m.state.value.needsLogin))
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
        m.updateEndpoint("192.168.1.10:49272")
        assertEquals(keys,m.state.value.cache.keys);assertEquals(command,m.state.value.cache.outbox.single().command)
        assertEquals("192.168.1.10:49272",m.state.value.cache.config!!.text("endpoint"));assertEquals("token",m.state.value.cache.token)
    }

    @Test fun offlineInitializationRetainsCachedWorkAndRetriesTheConnection() = runTest {
        val v=MemoryVault();val t=FakeTransport();t.configureFailure=true
        val m=model(v,t,backgroundScope);m.initialize()
        assertTrue(m.state.value.ready);assertFalse(m.state.value.needsLogin)
        assertEquals("Kitchen 🪴",m.state.value.cache.snapshot!!.records.single().data.text("title"))
        t.configureFailure=false;m.poll()
        assertEquals(2,t.configurations);assertTrue(m.state.value.online)
    }
    @Test fun unchangedHeadAvoidsDownloadingSnapshotAndChangedHeadSynchronizes() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        assertFalse(m.poll());assertEquals(listOf("/api/head"),t.requests)
        t.snapshot=t.snapshot.copy(sequence=2);assertTrue(m.poll())
        assertTrue("/api/snapshot" in t.requests);assertEquals(2,m.state.value.cache.snapshot!!.sequence)
    }
    @Test fun removalRequiresReviewedPendingWorkThenRevokesAndClearsLocalSecrets() = runTest {
        val v=MemoryVault();val t=FakeTransport();val m=model(v,t,backgroundScope);m.initialize()
        m.saveDraft("task",1,obj("notes" to "Keep me".j()))
        assertFailsWith<IllegalStateException> { m.removeInstallation() }
        assertFalse("/api/device/revoke-self" in t.requests)
        m.submitDraft("task");m.sync();m.removeInstallation()
        assertTrue(t.stopped);assertTrue(m.state.value.needsLogin)
        assertNull(v.value.token);assertNull(v.value.keys);assertNull(v.value.config)
        assertFalse(v.value.preferences.liveUpdates);assertFalse(v.value.preferences.reminders)
    }

}
