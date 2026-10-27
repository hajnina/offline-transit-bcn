package dev.hajnina.transit.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaptorOracleTest {

    private fun secs(h: Int, m: Int): Int = h * 3600 + m * 60

    private fun fixtureZip(): ByteArray {
        val out = ByteArrayOutputStream()
        val names = listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt")
        ZipOutputStream(out).use { z ->
            for (n in names) {
                z.putNextEntry(ZipEntry(n))
                z.write(javaClass.getResourceAsStream("/gtfs-sf/$n")!!.readBytes())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val day = LocalDate.of(2026, 10, 22)
    private val timetable = readGtfs(ByteArrayInputStream(fixtureZip()), day)
    private val router = Raptor()

    private fun haversineMeters(a: Stop, b: Stop): Double {
        val radius = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * radius * asin(sqrt(s.coerceIn(0.0, 1.0)))
    }

    private fun assertWithinFiveMinutes(expected: Int, actual: Int, what: String) {
        val diff = actual - expected
        assertTrue(diff in -300..300, "$what: expected ~$expected s, got $actual s (diff $diff s)")
    }

    private fun legStart(leg: Leg): String = when (leg) {
        is Leg.Ride -> leg.stopIds.first()
        is Leg.Walk -> leg.fromStopId
    }

    private fun legEnd(leg: Leg): String = when (leg) {
        is Leg.Ride -> leg.stopIds.last()
        is Leg.Walk -> leg.toStopId
    }

    @Test
    fun oracleSagradaToCatalunyaDirect() {
        val itineraries = router.route(timetable, Query("sagrada", "catalunya", departAt = secs(8, 0)))
        assertTrue(itineraries.isNotEmpty())
        assertWithinFiveMinutes(secs(8, 10), itineraries.first().arrives, "sagrada->catalunya best")
        val best = itineraries.first()
        assertTrue(best.legs.last() is Leg.Ride)
        val ride = best.legs.last() as Leg.Ride
        assertEquals(listOf("sagrada", "eixample", "catalunya"), ride.stopIds)
    }

    @Test
    fun oracleGloriaToFortunaOneTransfer() {
        val itineraries = router.route(timetable, Query("gloria", "fortuna", departAt = secs(8, 0)))
        assertTrue(itineraries.isNotEmpty())
        assertWithinFiveMinutes(secs(8, 10), itineraries.first().arrives, "gloria->fortuna best")
        val best = itineraries.first()
        assertEquals(1, best.transfers)
        val first = best.legs[0] as Leg.Ride
        val second = best.legs[1] as Leg.Ride
        assertEquals(listOf("gloria", "eixample"), first.stopIds)
        assertEquals(listOf("eixample", "fortuna"), second.stopIds)
    }

    @Test
    fun oracleSagradaToArestaWalkFromEixample() {
        val eixample = timetable.stops.getValue("eixample")
        val aresta = timetable.stops.getValue("aresta")
        val meters = haversineMeters(eixample, aresta)
        assertTrue(meters > 250 && meters < 400, "eixample->aresta should be ~311 m, was $meters")
        val walkSecs = ceil(meters / 1.2).toInt()
        val expected = secs(8, 5) + walkSecs

        val itineraries = router.route(timetable, Query("sagrada", "aresta", departAt = secs(8, 0)))
        assertTrue(itineraries.isNotEmpty())
        val best = itineraries.first()
        assertWithinFiveMinutes(expected, best.arrives, "sagrada->aresta best")
        val last = best.legs.last()
        assertTrue(last is Leg.Walk, "last leg should be a walk, was $last")
        val walk = last as Leg.Walk
        assertEquals("eixample", walk.fromStopId)
        assertEquals("aresta", walk.toStopId)
        assertEquals(walkSecs, ceil(walk.meters / 1.2).toInt())
    }

    @Test
    fun oracleSagradaToCotxeraOnlyOnT1() {
        val itineraries = router.route(timetable, Query("sagrada", "cotxera", departAt = secs(8, 0)))
        assertTrue(itineraries.isNotEmpty())
        assertWithinFiveMinutes(secs(8, 14), itineraries.first().arrives, "sagrada->cotxera best")
    }

    private fun sweep(from: String, to: String) {
        val itineraries = router.route(timetable, Query(from, to, departAt = secs(8, 0)))
        assertTrue(itineraries.isNotEmpty(), "no itinerary found for $from->$to")
        for (itin in itineraries) {
            assertTrue(itin.legs.isNotEmpty(), "empty itinerary legs for $from->$to")
            for ((i, leg) in itin.legs.withIndex()) {
                if (i == 0) {
                    assertEquals(from, legStart(leg), "itinerary $i does not start at $from")
                } else {
                    val prev = itin.legs[i - 1]
                    assertEquals(legEnd(prev), legStart(leg), "leg $i boarding/continuity mismatch")
                    val prevArrival = when (prev) {
                        is Leg.Ride -> prev.arrives
                        is Leg.Walk -> prev.arrives
                    }
                    val departs = when (leg) {
                        is Leg.Ride -> leg.departs
                        is Leg.Walk -> leg.departs
                    }
                    if (prev is Leg.Walk) {
                        assertEquals(prevArrival, departs, "leg $i departure != previous walk arrival")
                    } else if (leg is Leg.Walk) {
                        assertEquals(prevArrival, departs, "walk leg $i departs != previous ride arrival")
                    } else {
                        assertTrue(departs >= prevArrival, "leg $i departs before previous leg arrival")
                    }
                }
                when (leg) {
                    is Leg.Ride -> {
                        assertTrue(leg.stopIds.isNotEmpty(), "ride leg $i has empty stopIds")
                        val trip = timetable.trips.first { it.id == leg.tripId }
                        val start = trip.stopIds.indexOf(leg.stopIds.first())
                        assertTrue(start >= 0, "leg $i first stop not in trip ${leg.tripId}")
                        assertTrue(
                            trip.stopIds.size - start >= leg.stopIds.size &&
                                trip.stopIds.subList(start, start + leg.stopIds.size) == leg.stopIds,
                            "leg $i stopIds not contiguous sublist of trip ${leg.tripId}",
                        )
                        assertEquals(trip.departures[start], leg.departs, "leg $i departs != trip departure")
                        assertEquals(
                            trip.arrivals[start + leg.stopIds.size - 1],
                            leg.arrives,
                            "leg $i arrives != trip arrival",
                        )
                    }
                    is Leg.Walk -> {
                        assertEquals(
                            ceil(leg.meters / 1.2).toInt(),
                            leg.arrives - leg.departs,
                            "walk leg $i duration != meters/speed",
                        )
                    }
                }
            }
            assertEquals(to, legEnd(itin.legs.last()), "itinerary does not end at $to")
        }
    }

    @Test
    fun continuitySweepSagradaToCatalunya() = sweep("sagrada", "catalunya")

    @Test
    fun continuitySweepGloriaToFortuna() = sweep("gloria", "fortuna")

    @Test
    fun continuitySweepSagradaToCotxera() = sweep("sagrada", "cotxera")
}
