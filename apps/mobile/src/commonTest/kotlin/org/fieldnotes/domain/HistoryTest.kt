package org.fieldnotes.domain

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private val historyActor=Actor("owner","Alex","owner","session")
private fun historyFixture()=Cache(keys=obj("private_key" to "identity-kept".j()),config=obj("endpoint" to "private-tunnel".j()),token="session",snapshot=Snapshot(1,"epoch",1,3,emptyList(),historyActor),compatibility=ServerCompatibility(version="new",features=setOf(HISTORY_FEATURE)))
private val historyChange=HistoryChange("notes","Notes","Green for later".j(),"Yellow for tomorrow".j())
private val historyEntry=HistoryEntry(3,"task","task.update","sophia","Sophia",1_793_890_800,"Bananas","Costco Shopping List","task","grocery_item",changes=listOf(historyChange),details="recorded")
private class HistoryVault(var stored:Cache=historyFixture()):Vault {
    override suspend fun load()=stored
    override suspend fun save(cache:Cache){stored=WireJson.decodeFromString(WireJson.encodeToString(cache))}
    override suspend fun clearImages(){}
}
private class HistoryTransport:Transport {
    val paths=mutableListOf<String>();var snapshot=historyFixture().snapshot!!;var unavailable=false;var responseActor=historyActor.user_id
    override suspend fun keys()=error("Existing identity must be kept")
    override suspend fun configure(configuration:JsonObject){}
    override suspend fun stop(){}
    override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?):Reply {
        paths+=path
        return when(path){
            "/api/history"->if(unavailable)Reply(503,"""{"error":"History unavailable"}""") else Reply(200,WireJson.encodeToString(HistoryPage(snapshot.epoch,snapshot.generation,3,listOf(historyEntry),3,false,responseActor)))
            "/api/login"->Reply(200,obj("token" to "other-session".j(),"actor" to WireJson.encodeToJsonElement(historyActor.copy(user_id="other",name="Other"))).toString())
            "/api/snapshot"->Reply(200,WireJson.encodeToString(snapshot))
            "/api/people"->Reply(200,obj("people" to JsonArray(emptyList())).toString())
            "/api/head"->Reply(200,obj("epoch" to snapshot.epoch.j(),"generation" to snapshot.generation.j(),"sequence" to snapshot.sequence.j(),"compatibility" to obj("server_version" to "new".j(),"protocol_min" to 1.j(),"protocol_max" to 1.j(),"minimum_client_code" to 10.j(),"recommended_client_code" to CURRENT_CLIENT_CODE.j(),"features" to JsonArray(listOf(HISTORY_FEATURE.j())))).toString())
            else->error("Unexpected history path")
        }
    }
}
class HistoryTest {
    @Test fun retentionRevisionDropsPurgedCacheInsteadOfMergingItBack() {
        val original=historyFixture();val page=HistoryPage("epoch",1,3,listOf(historyEntry),3,false,historyActor.user_id)
        val cached=original.copy(history=mergeHistory(original,page,123))
        assertNotNull(cached.currentHistory())
        val changed=cached.copy(snapshot=cached.snapshot!!.copy(history_revision=1))
        assertNull(changed.currentHistory())
        val empty=page.copy(entries=emptyList(),history_revision=1)
        assertTrue(mergeHistory(changed,empty,124).entries.isEmpty())
    }

    @Test fun acceptedHistorySurvivesRestartAndOfflineFiltersSearchBothSidesOfChanges()=runTest {
        val vault=HistoryVault();val transport=HistoryTransport();val model=Workspace(transport,vault,{"unused"},backgroundScope)
        model.initialize();val originalKeys=vault.stored.keys
        model.history(HistoryFilter(),TimeZone.UTC)
        val restarted=Workspace(transport,vault,{"unused"},backgroundScope);restarted.initialize()
        assertEquals(listOf(historyEntry),restarted.state.value.cache.currentHistory()!!.entries)
        assertTrue(historyMatches(historyEntry,HistoryFilter(query="green for later"),TimeZone.UTC))
        assertTrue(historyMatches(historyEntry,HistoryFilter(query="Costco"),TimeZone.UTC))
        assertFalse(historyMatches(historyEntry,HistoryFilter(action="removed"),TimeZone.UTC))
        transport.unavailable=true
        assertFails {restarted.history(HistoryFilter(),TimeZone.UTC)}
        assertEquals(listOf(historyEntry),vault.stored.currentHistory()!!.entries);assertEquals(originalKeys,vault.stored.keys)
    }
    @Test fun wrongAccountResponsesCannotBecomeCachedHistoryAndWorkspaceClearDropsOldHistory()=runTest {
        val vault=HistoryVault();val transport=HistoryTransport();val model=Workspace(transport,vault,{"unused"},backgroundScope)
        model.initialize();transport.responseActor="other"
        assertFails {model.history(HistoryFilter(),TimeZone.UTC)};assertNull(vault.stored.history)
        transport.responseActor=historyActor.user_id;model.history(HistoryFilter(),TimeZone.UTC)
        transport.snapshot=transport.snapshot.copy(generation=2,sequence=4)
        model.sync();assertNull(vault.stored.history);assertEquals(2,vault.stored.snapshot!!.generation)
    }
    @Test fun historyWaitingBehindAnAccountSwitchIsRejectedBeforeSendingItsRequest()=runTest {
        val vault=HistoryVault();val transport=HistoryTransport();val model=Workspace(transport,vault,{"unused"},backgroundScope);model.initialize()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=backgroundScope.launch {model.portableSnapshot {entered.complete(Unit);release.await()}}
        entered.await()
        val switch=backgroundScope.launch {model.login("Other","not-stored")};runCurrent()
        var refused=false
        val history=backgroundScope.launch {try{model.history(HistoryFilter(),TimeZone.UTC)}catch(_:IllegalStateException){refused=true}}
        runCurrent();release.complete(Unit);holder.join();switch.join();history.join()
        assertTrue(refused);assertFalse("/api/history" in transport.paths);assertNull(vault.stored.history)
    }
    @Test fun localDateFiltersIncludeTheWholeClockChangeDayAndTimesStayLocal() {
        val zone=TimeZone.of("America/New_York")
        val filter=HistoryFilter(from="2026-11-01",to="2026-11-01").body(Basis("epoch",1),zone)
        assertEquals(25*3600L,filter.number("to")-filter.number("from"))
        val whenChanged=historyChange.copy(field="starts_at",after=Instant.parse("2026-11-05T15:00:00Z").epochSeconds.j())
        assertContains(historyValue(whenChanged,whenChanged.after,zone),"10:00 AM")
        assertEquals("Green for later",historyValue(historyChange,historyChange.before,zone))
        assertEquals("Grocery item",historyEntry.typeLabel)
    }
}
