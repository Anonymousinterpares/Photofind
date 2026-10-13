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

fun formatRange(range: DateRange): String =
    "${formatShortDate(range.startMillis)} – ${formatShortDate(range.endMillis)}"

/** Material date pickers report UTC midnights; turn them into whole local days. */
fun pickerToRange(startUtcMillis: Long, endUtcMillis: Long): DateRange {
    val first = Instant.ofEpochMilli(startUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val last = Instant.ofEpochMilli(endUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return DateRange(
        startMillis = first.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMillis = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
    )
}

fun rangeToPicker(range: DateRange): Pair<Long, Long> = Pair(
    localDate(range.startMillis).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    localDate(range.endMillis).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
)
