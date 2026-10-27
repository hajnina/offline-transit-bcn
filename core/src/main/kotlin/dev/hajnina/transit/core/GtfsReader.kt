package dev.hajnina.transit.core

import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** Reads a GTFS zip and resolves it to one service day. Only java.util.zip; hours may exceed 24. */
fun readGtfs(zip: InputStream, serviceDay: LocalDate): Timetable {
    val files = HashMap<String, String>()
    ZipInputStream(zip.buffered()).use { zs ->
        while (true) {
            val e = zs.nextEntry ?: break
            if (!e.isDirectory) files[e.name.substringAfterLast('/')] = zs.readBytes().decodeToString()
            zs.closeEntry()
        }
    }
    val rows = HashMap<String, List<Map<String, String>>>()
    for (name in listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt")) {
        if (files[name] != null) rows[name.removeSuffix(".txt")] = parseCsv(files[name]!!)
    }

    val stops = (rows["stops"] ?: emptyList()).associate {
        it.require("stop_id") to Stop(it.require("stop_id"), it["stop_name"] ?: "", it.dbl("stop_lat"), it.dbl("stop_lon"))
    }
    val lines = (rows["routes"] ?: emptyList()).associate {
        it.require("route_id") to Line(it.require("route_id"), it["route_short_name"] ?: "", it["route_color"]?.takeIf { c -> c.isNotBlank() }?.let { c -> if (c.startsWith("#")) c else "#$c" } ?: "")
    }

    val active = servicesForDay(rows, serviceDay)

    val timesByTrip = HashMap<String, MutableList<Map<String, String>>>()
    for (st in rows["stop_times"] ?: emptyList()) timesByTrip.getOrPut(st.require("trip_id")) { mutableListOf() }.add(st)

    val trips = (rows["trips"] ?: emptyList()).filter { it.require("service_id") in active }.map { t ->
        val id = t.require("trip_id")
        val sts = (timesByTrip[id] ?: emptyList()).sortedBy { it["stop_sequence"]?.toIntOrNull() ?: 0 }
        Trip(
            id = id,
            lineId = t.require("route_id"),
            headsign = t["trip_headsign"] ?: "",
            stopIds = sts.map { it.require("stop_id") },
            arrivals = sts.map { it.timeSec("arrival_time") },
            departures = sts.map { it.timeSec("departure_time") },
        )
    }.filter { it.stopIds.size >= 2 }
    return Timetable(stops, lines, trips)
}

private val GTFS_DATE = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd")

/** calendar.txt range+weekday if present, overridden per-service by calendar_dates exceptions (1 add, 2 remove). */
private fun servicesForDay(rows: Map<String, List<Map<String, String>>>, day: LocalDate): Set<String> {
    val active = HashSet<String>()
    for (c in rows["calendar"] ?: emptyList()) {
        val start = LocalDate.parse(c.require("start_date"), GTFS_DATE)
        val end = LocalDate.parse(c.require("end_date"), GTFS_DATE)
        val flags = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val dow = day.dayOfWeek.value - 1
        if (day >= start && day <= end && c[flags[dow]] == "1") active.add(c.require("service_id"))
    }
    for (x in rows["calendar_dates"] ?: emptyList()) {
        if (LocalDate.parse(x.require("date"), GTFS_DATE) != day) continue
        when (x["exception_type"]) {
            "1" -> active.add(x.require("service_id"))
            "2" -> active.remove(x.require("service_id"))
        }
    }
    return active
}

/** RFC4180 subset: quoted fields with escaped "" quotes, CRLF and BOM tolerated. */
private fun parseCsv(text: String): List<Map<String, String>> {
    val clean = text.removePrefix("﻿").replace("\r\n", "\n")
    val lines = clean.split('\n').filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()
    val header = splitLine(lines.first())
    return lines.drop(1).filter { it.isNotBlank() }.map { row ->
        val cells = splitLine(row)
        header.mapIndexed { i, h -> h to cells.getOrElse(i) { "" } }.toMap()
    }
}

private fun splitLine(line: String): List<String> {
    val cells = ArrayList<String>()
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        when (val ch = line[i]) {
            '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else quoted = !quoted
            ',' -> if (quoted) sb.append(ch) else { cells.add(sb.toString()); sb.clear() }
            else -> sb.append(ch)
        }
        i++
    }
    cells.add(sb.toString())
    return cells
}

/** HH:MM:SS since midnight, NOT wrapped (25:10:00 = 90600); empty/missing = 0. */
private fun Map<String, String>.timeSec(field: String): Int {
    val v = this[field] ?: return 0
    if (v.isBlank()) return 0
    val (h, m, s) = v.split(':').map { it.trim().toInt() }
    return h * 3600 + m * 60 + s
}

private fun Map<String, String>.require(key: String): String = this[key] ?: error("GTFS column '$key' missing")

private fun Map<String, String>.dbl(key: String): Double = this[key]?.takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 0.0
