package org.fieldnotes.domain

import kotlinx.datetime.*

data class FederalHoliday(val date:LocalDate,val name:String)
fun federalHolidays(year:Int):List<FederalHoliday> {
    val holidays=mutableListOf<FederalHoliday>()
    fun add(date:LocalDate,name:String){if(date.year==year)holidays+=FederalHoliday(date,name)}
    for(y in year-1..year+1){
        for((month,day,name) in listOf(Triple(1,1,"New Year’s Day"),Triple(6,19,"Juneteenth National Independence Day"),Triple(7,4,"Independence Day"),Triple(11,11,"Veterans Day"),Triple(12,25,"Christmas Day"))){
            if(month==6&&y<2021)continue
            val date=LocalDate(y,month,day);add(date,name)
            if(date.dayOfWeek==DayOfWeek.SATURDAY)add(date.minus(1,DateTimeUnit.DAY),"$name (observed)")
            if(date.dayOfWeek==DayOfWeek.SUNDAY)add(date.plus(1,DateTimeUnit.DAY),"$name (observed)")
        }
        fun weekday(month:Int,weekday:DayOfWeek,nth:Int,name:String){
            val first=LocalDate(y,month,1)
            val date=if(nth<0){val last=first.plus(1,DateTimeUnit.MONTH).minus(1,DateTimeUnit.DAY);last.minus((last.dayOfWeek.isoDayNumber-weekday.isoDayNumber+7)%7,DateTimeUnit.DAY)}else first.plus((weekday.isoDayNumber-first.dayOfWeek.isoDayNumber+7)%7+(nth-1)*7,DateTimeUnit.DAY)
            add(date,name)
        }
        if(y>=1986)weekday(1,DayOfWeek.MONDAY,3,"Martin Luther King Jr. Day")
        weekday(2,DayOfWeek.MONDAY,3,"Washington’s Birthday");weekday(5,DayOfWeek.MONDAY,-1,"Memorial Day")
        weekday(9,DayOfWeek.MONDAY,1,"Labor Day");weekday(10,DayOfWeek.MONDAY,2,"Columbus Day");weekday(11,DayOfWeek.THURSDAY,4,"Thanksgiving Day")
    }
    return holidays.sortedBy{it.date}
}
fun holidaysOn(day:LocalDate,enabled:Boolean)=if(enabled)federalHolidays(day.year).filter{it.date==day}.map{it.name}else emptyList()
