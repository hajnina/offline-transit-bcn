package dev.hajnina.transit.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

const val CLI_USAGE = "usage: cli <gtfs-zip-or-dir> <fromStopId> <toStopId> [HH:MM] [YYYY-MM-DD]"

fun main(args: Array<String>) {
    println(run(args.toList()))
}

/** Pure: builds a timetable from a GTFS zip-or-directory, routes it, and returns the printed text. */
fun run(args: List<String>): String {
    if (args.size < 3) return CLI_USAGE
    val source = args[0]
    val from = args[1]
    val to = args[2]
    var serviceDay = LocalDate.now()
    val departAt: Secs = if (args.size >= 4) {
        parseHm(args[3]) ?: return CLI_USAGE
    } else {
        val now = LocalTime.now()
        now.hour * 3600 + now.minute * 60 + now.second
    }
    if (args.size >= 5) {
        if (args[4] == "--date" && args.size >= 6) {
            serviceDay = parseYmd(args[5]) ?: return CLI_USAGE
        } else {
            serviceDay = parseYmd(args[4]) ?: return CLI_USAGE
        }
    }
    val timetable = try {
        loadGtfs(source, serviceDay)
    } catch (e: Exception) {
        return CLI_USAGE
    } ?: return CLI_USAGE
    val itineraries = Raptor().route(
        timetable,
        Query(fromStopId = from, toStopId = to, departAt = departAt),
    )
    if (itineraries.isEmpty()) return "no itineraries"
    return itineraries.mapIndexed { i, it -> formatItinerary(i, it, timetable) }
        .joinToString("\n", postfix = "\n")
}

/** Returns null when source is neither a readable zip file nor a directory with GTFS files. */
private fun loadGtfs(source: String, serviceDay: LocalDate): Timetable? {
    val f = File(source)
    if (!f.exists()) return null
    val zip: InputStream = when {
        f.isFile -> f.inputStream()
        f.isDirectory -> zipDirectory(f) ?: return null
        else -> return null
    }
    return zip.use { readGtfs(it, serviceDay) }
}

/** Zips the top-level files of a directory in memory, entries named exactly "stops.txt" etc. */
private fun zipDirectory(dir: File): InputStream? {
    val names = listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt")
        .filter { File(dir, it).isFile }
    if (!names.containsAll(listOf("stops.txt", "trips.txt", "stop_times.txt"))) return null
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { out ->
        for (name in names) {
            out.putNextEntry(ZipEntry(name))
            File(dir, name).inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
    return ByteArrayInputStream(bytes.toByteArray())
}

private fun parseHm(text: String): Secs? {
    val m = Regex("^\\d{1,2}:\\d{2}$").matchEntire(text.trim()) ?: return null
    val (h, mm) = m.value.split(':').map { it.toInt() }
    if (h > 29 || mm > 59) return null
    return h * 3600 + mm * 60
}

private fun parseYmd(text: String): LocalDate? = try {
    LocalDate.parse(text.trim())
} catch (e: Exception) {
    null
}

private fun formatSecs(s: Secs): String = String.format(
    "%02d:%02d:%02d",
    s / 3600,
    s % 3600 / 60,
    s % 60,
)

private fun formatItinerary(index: Int, it: Itinerary, tt: Timetable): String {
    val sb = StringBuilder()
    sb.appendLine("$index: departs ${formatSecs(it.departs)} arrives ${formatSecs(it.arrives)} (${it.transfers} transfers)")
    for (leg in it.legs) {
        when (leg) {
            is Leg.Ride -> {
                val fromStop = tt.stops[leg.stopIds.first()]?.name ?: leg.stopIds.first()
                val toStop = tt.stops[leg.stopIds.last()]?.name ?: leg.stopIds.last()
                val line = tt.lines[leg.lineId]?.shortName ?: leg.lineId
                sb.appendLine(
                    "  ride $line ${leg.headsign}: $fromStop ${formatSecs(leg.departs)} -> $toStop ${formatSecs(leg.arrives)} (${leg.stopIds.size} stops)",
                )
            }
            is Leg.Walk -> {
                val fromStop = tt.stops[leg.fromStopId]?.name ?: leg.fromStopId
                val toStop = tt.stops[leg.toStopId]?.name ?: leg.toStopId
                sb.appendLine("  walk $fromStop -> $toStop (${leg.meters.roundToInt()} m, ${leg.arrives - leg.departs} s)")
            }
        }
    }
    return sb.trimEnd().toString()
}
