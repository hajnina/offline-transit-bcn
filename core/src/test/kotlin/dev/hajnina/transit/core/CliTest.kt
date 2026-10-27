package dev.hajnina.transit.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {

    private fun tempGtfsDir(): File {
        val dir = Files.createTempDirectory("cli-gtfs").toFile()
        for (name in listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt")) {
            val res = javaClass.getResourceAsStream("/gtfs-sf/$name")
                ?: throw IllegalStateException("fixture resource /gtfs-sf/$name missing")
            res.use { input -> FileOutputStream(File(dir, name)).use { input.copyTo(it) } }
        }
        return dir
    }

    private fun zipDir(dir: File): File {
        val zip = File.createTempFile("cli-gtfs", ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            for (f in dir.listFiles()!!.sortedBy { it.name }) {
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun directorySource_printsItineraryWithStopNamesAndTimes() {
        val out = run(listOf(tempGtfsDir().path, "sagrada", "catalunya", "08:00", "2026-10-22"))
        assertTrue(out.contains("0: departs 08:02:00 arrives 08:10:00 (0 transfers)"), out)
        assertTrue(out.contains("ride V17"), out)
        assertTrue(out.contains("Sagrada Familia 08:02:00 -> Placa Catalunya 08:10:00 (3 stops)"), out)
    }

    @Test
    fun zipSource_sameResultAsDirectory() {
        val dir = tempGtfsDir()
        val fromZip = run(listOf(zipDir(dir).path, "sagrada", "catalunya", "08:00", "2026-10-22"))
        val fromDir = run(listOf(dir.path, "sagrada", "catalunya", "08:00", "2026-10-22"))
        assertEquals(fromDir, fromZip)
        assertTrue(fromZip.contains("Placa Catalunya 08:10:00"), fromZip)
    }

    @Test
    fun tooFewArgs_returnsUsageWithoutException() {
        assertEquals(CLI_USAGE, run(emptyList()))
        assertEquals(CLI_USAGE, run(listOf("x")))
        assertEquals(CLI_USAGE, run(listOf("x", "sagrada")))
    }

    @Test
    fun badTimeOrDateOrMissingSource_returnsUsage() {
        val dir = tempGtfsDir()
        assertEquals(CLI_USAGE, run(listOf(dir.path, "sagrada", "catalunya", "25:99")))
        assertEquals(CLI_USAGE, run(listOf(dir.path, "sagrada", "catalunya", "08:00", "not-a-date")))
        assertEquals(CLI_USAGE, run(listOf("/no/such/path", "sagrada", "catalunya")))
        assertEquals("no itineraries", run(listOf(dir.path, "sagrada", "sagrada")).trim())
    }

    @Test
    fun unknownStops_printNoItineraries() {
        val out = run(listOf(tempGtfsDir().path, "ghost", "catalunya", "08:00", "2026-10-22"))
        assertEquals("no itineraries", out.trim())
    }
}
