package dev.hajnina.transit.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GtfsReaderTest {
    private val day = LocalDate.of(2026, 5, 4)

    private fun zip(namesAndFiles: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            namesAndFiles.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun fixtureZip(): ByteArray {
        val files = HashMap<String, String>()
        for (n in listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt")) {
            files[n] = javaClass.getResourceAsStream("/gtfs/$n")!!.readBytes().decodeToString()
        }
        return zip(files)
    }

    @Test
    fun readsFixtureIntoTimetable() {
        val tt = readGtfs(ByteArrayInputStream(fixtureZip()), day)
        assertEquals(setOf("S1", "S2", "S3"), tt.stops.keys)
        assertEquals(41.4036, tt.stops["S1"]!!.lat)
        assertEquals(setOf("L1"), tt.lines.keys)
        assertEquals(Line("L1", "V15", "#D22E2E"), tt.lines["L1"])
        assertEquals(listOf("T1", "T2"), tt.trips.map { it.id })
    }

    @Test
    fun tripsCarryOrderedStopsAndUnwrappedTimes() {
        val tt = readGtfs(ByteArrayInputStream(fixtureZip()), day)
        val t2 = tt.trips.first { it.id == "T2" }
        assertEquals(listOf("S1", "S2", "S3"), t2.stopIds)
        assertEquals(listOf(25 * 3600 + 10 * 60, 25 * 3600 + 13 * 60, 25 * 3600 + 16 * 60), t2.arrivals)
        assertTrue(t2.arrivals.zip(t2.departures).all { (a, d) -> a <= d })
    }

    @Test
    fun calendarDatesExceptionActivatesServiceOnQueriedDay() {
        val tt = readGtfs(ByteArrayInputStream(fixtureZip()), day)
        assertEquals(2, tt.trips.size)
        val offDay = LocalDate.of(2026, 5, 3)
        assertEquals(0, readGtfs(ByteArrayInputStream(fixtureZip()), offDay).trips.size)
        val exceptionDay = LocalDate.of(2026, 5, 5)
        assertEquals(0, readGtfs(ByteArrayInputStream(fixtureZip()), exceptionDay).trips.size)
    }

    @Test
    fun removedExceptionDropsService() {
        val z = zip(mapOf(
            "calendar.txt" to javaClass.getResourceAsStream("/gtfs/calendar.txt")!!.readBytes().decodeToString(),
            "calendar_dates.txt" to "service_id,date,exception_type\nWD,20260504,2\n",
        ))
        assertEquals(0, readGtfs(ByteArrayInputStream(z), day).trips.size)
    }

    @Test
    fun zipWithoutCalendarFilesUsesOnlyExceptions() {
        val z = zip(mapOf(
            "stops.txt" to javaClass.getResourceAsStream("/gtfs/stops.txt")!!.readBytes().decodeToString(),
            "routes.txt" to javaClass.getResourceAsStream("/gtfs/routes.txt")!!.readBytes().decodeToString(),
            "trips.txt" to javaClass.getResourceAsStream("/gtfs/trips.txt")!!.readBytes().decodeToString(),
            "stop_times.txt" to javaClass.getResourceAsStream("/gtfs/stop_times.txt")!!.readBytes().decodeToString(),
            "calendar_dates.txt" to javaClass.getResourceAsStream("/gtfs/calendar_dates.txt")!!.readBytes().decodeToString(),
        ))
        val tt = readGtfs(ByteArrayInputStream(z), day)
        assertEquals(listOf("T1", "T2"), tt.trips.map { it.id })
        assertEquals(0, readGtfs(ByteArrayInputStream(z), LocalDate.of(2026, 5, 6)).trips.size)
    }
}
