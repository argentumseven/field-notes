package org.fieldnotes.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.fieldnotes.domain.*

@Serializable private data class AdditionDelivery(val scope:String="",val sequence:Long=0,val pending:List<AdditionNotice> = emptyList(),val sourceAfter:Long=0)
/** A persisted intent precedes notification delivery, then an encrypted cursor
 * acknowledges it. Reopening the app does not replay household history. */
object SharedAdditions {
    private val mutex=Mutex()
    suspend fun reconcile(app:FieldNotesApplication)=mutex.withLock {
        val state=app.model.state.value;val cache=state.cache;val snapshot=cache.snapshot
        if(!state.ready||state.needsLogin||cache.token==null||snapshot==null)return@withLock
        val scope=Alerts.digest("${cache.config?.text("server_key")}:${snapshot.actor.user_id}:${snapshot.epoch}:${snapshot.generation}")
        val bytes=app.vault.readEffect("shared-additions")
        var saved=if(bytes==null)AdditionDelivery() else try {WireJson.decodeFromString<AdditionDelivery>(bytes.decodeToString())}finally{bytes.fill(0)}
        suspend fun persist(value:AdditionDelivery){val raw=WireJson.encodeToString(value).encodeToByteArray();try{app.vault.writeEffect("shared-additions",raw)}finally{raw.fill(0)};saved=value}
        if(saved.scope!=scope||!cache.preferences.sharedAdditions){persist(AdditionDelivery(scope,snapshot.sequence));return@withLock}
        if(saved.pending.isEmpty()&&snapshot.sequence>saved.sequence)persist(AdditionDelivery(scope,snapshot.sequence,sharedAdditionNotices(cache,saved.sequence),saved.sequence))
        if(saved.pending.isEmpty())return@withLock
        if(!Alerts.allowed(app,Alerts.UPDATES))return@withLock
        val stillAllowed=sharedAdditionNotices(cache,saved.sourceAfter,saved.sequence)
        for(notice in stillAllowed) {
            val current=app.model.state.value.cache
            if(current.snapshot?.actor?.user_id!=snapshot.actor.user_id||current.snapshot?.basis!=snapshot.basis||!current.preferences.sharedAdditions)return@withLock
            val reveal=cache.preferences.notificationTitles
            if(!Alerts.post(app,Alerts.UPDATES,notice.key,if(reveal)notice.title else "New household item",if(reveal)notice.summary else "Open Field Notes to view it.",notice.target,if(reveal)notice.details else "Household notification details are hidden in your visibility settings."))return@withLock
        }
        persist(saved.copy(pending=emptyList()))
    }
}
