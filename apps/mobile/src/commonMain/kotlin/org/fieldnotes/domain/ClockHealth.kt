package org.fieldnotes.domain
import kotlinx.serialization.json.*
/** An authenticated comparison, never permission to set either device clock. */
fun clockWarning(head:JsonObject,startMs:Long,endMs:Long,elapsedMs:Long):String? {
    val server=head.number("server_time_ms")
    if(server<=0)return null // Older paired servers did not publish diagnostics.
    if(elapsedMs !in 0..10_000 || kotlin.math.abs(endMs-startMs-elapsedMs)>2_000)return "Time check interrupted. Check again when the connection is stable."
    val midpoint=startMs+(endMs-startMs)/2
    if(kotlin.math.abs(server-midpoint)>30_000+elapsedMs/2)return "Phone and server time differ by more than 30 seconds. Check their time settings before relying on scheduled reminders."
    return when((head["clock_health"] as? JsonObject)?.text("status")) {
        "unsynchronized"->"Server time is not synchronized. Check the Linux time service."
        "stale","clock_jump"->"Server time verification is stale or its clock changed. Check the Linux time service."
        "unknown"->"Server clock synchronization could not be verified."
        else->null
    }
}
