package org.fieldnotes.domain

import kotlinx.datetime.*

data class DayBlock(val record:Record,val from:Double,val until:Double?,val column:Int=0,val columns:Int=1)
fun dayLengthMinutes(day:LocalDate,zone:TimeZone)=(day.plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone)-day.atStartOfDayIn(zone)).inWholeMinutes.toInt()
/** Coordinates use elapsed local-day minutes, so 23/25-hour clock-change days
 * keep their real duration. Start-only markers never contribute occupied time. */
fun dayBlocks(records:List<Record>,day:LocalDate,zone:TimeZone):List<DayBlock> {
    val start=day.atStartOfDayIn(zone).epochSeconds;val end=day.plus(1,DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
    val blocks=calendarEntries(records,day,zone).filter { it.startsAt!=null }.map { e ->
        val from=e.record.data.number("starts_at");val until=e.record.data.number("ends_at")
        DayBlock(e.record,(maxOf(start,from)-start)/60.0,until.takeIf { it>0 }?.let { (minOf(end,it)-start)/60.0 })
    }.sortedWith(compareBy<DayBlock> { it.from }.thenBy { it.until ?: it.from })
    val result=mutableListOf<DayBlock>();var cluster=mutableListOf<DayBlock>();var clusterEnd=-1.0
    fun flush() {
        val columns=mutableListOf<Double>();val placed=cluster.map { block ->
            var column=columns.indexOfFirst { it<=block.from }
            if(column<0){column=columns.size;columns.add(0.0)}
            columns[column]=block.until ?: block.from+15
            block.copy(column=column)
        }
        result+=placed.map { it.copy(columns=columns.size.coerceAtLeast(1)) };cluster=mutableListOf();clusterEnd=-1.0
    }
    for(block in blocks) {
        if(cluster.isNotEmpty()&&block.from>=clusterEnd)flush()
        cluster+=block;clusterEnd=maxOf(clusterEnd,block.until ?: block.from+15)
    }
    flush();return result
}
fun occupiedFraction(records:List<Record>,start:Long,end:Long,zone:TimeZone=TimeZone.currentSystemDefault()):Double {
    val from=Instant.fromEpochSeconds(start).toLocalDateTime(zone).date;val until=Instant.fromEpochSeconds(end).toLocalDateTime(zone).date.plus(1,DateTimeUnit.DAY)
    val spans=records.flatMap { eventInstances(it,from,until,zone) }.filter { it.kind=="event"&&!it.flag("all_day")&&it.data.number("ends_at")>start&&it.data.number("starts_at")<end }
        .map { maxOf(start,it.data.number("starts_at")) to minOf(end,it.data.number("ends_at")) }.filter { it.second>it.first }.sortedBy { it.first }
    var covered=0L;var last=start
    for((from,until) in spans){covered+=(until-maxOf(last,from)).coerceAtLeast(0);last=maxOf(last,until)}
    return covered.toDouble()/(end-start).coerceAtLeast(1)
}
