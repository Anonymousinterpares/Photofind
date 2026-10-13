package com.photofind.ui

import com.photofind.search.DateRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val zone: ZoneId get() = ZoneId.systemDefault()

private val dayHeader = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)

fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

fun formatDay(date: LocalDate): String = date.format(dayHeader)

fun formatShortDate(millis: Long): String = localDate(millis).format(shortDate)

fun formatDateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(dateTime)

private val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy")

fun formatMonthYear(millis: Long): String =
    localDate(millis).format(monthYear).replaceFirstChar { it.titlecase() }

fun formatRange(range: DateRange): String {
    val first = localDate(range.startMillis)
    val last = localDate(range.endMillis)
    return when {
        first == last -> first.format(shortDate)
        first.dayOfYear == 1 && last == first.withDayOfYear(first.lengthOfYear()) -> "${first.year}"
        first.dayOfMonth == 1 && last == first.withDayOfMonth(first.lengthOfMonth()) ->
            formatMonthYear(range.startMillis)
        else -> "${first.format(shortDate)} – ${last.format(shortDate)}"
    }
}

/** Whole local days from [first] to [last], inclusive. */
fun daysRange(first: LocalDate, last: LocalDate): DateRange = DateRange(
    startMillis = first.atStartOfDay(zone).toInstant().toEpochMilli(),
    endMillis = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
)

data class Period(val label: String, val range: DateRange)

/** Quick choices for the date filter, then one entry per year present in the library. */
fun quickPeriods(years: List<Int>): List<Period> {
    val today = LocalDate.now(zone)
    val presets = listOf(
        Period("Last 7 days", daysRange(today.minusDays(6), today)),
        Period("Last 30 days", daysRange(today.minusDays(29), today)),
        Period("This month", daysRange(today.withDayOfMonth(1), today)),
        Period("Last 12 months", daysRange(today.minusMonths(12).plusDays(1), today)),
    )
    val perYear = years.map { year ->
        Period("$year", daysRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
    }
    return presets + perYear
}

/** Material date pickers report UTC midnights; turn them into whole local days. */
fun pickerToRange(startUtcMillis: Long, endUtcMillis: Long): DateRange = daysRange(
    Instant.ofEpochMilli(startUtcMillis).atZone(ZoneOffset.UTC).toLocalDate(),
    Instant.ofEpochMilli(endUtcMillis).atZone(ZoneOffset.UTC).toLocalDate(),
)

fun rangeToPicker(range: DateRange): Pair<Long, Long> = Pair(
    localDate(range.startMillis).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    localDate(range.endMillis).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
)
