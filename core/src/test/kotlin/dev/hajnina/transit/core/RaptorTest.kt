package dev.hajnina.transit.core

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaptorTest {

    private val stops = listOf(
        Stop("sagrada", "Sagrada Familia", 41.4036, 2.1744),
        Stop("gloria", "Placa de les Glories", 41.4020, 2.1720),
        Stop("eixample", "Eixample", 41.3950, 2.1700),
        Stop("fortuna", "Fortuna", 41.3980, 2.1600),
        Stop("cotxera", "Cotxera", 41.3897, 2.1680),
        Stop("catalunya", "Placa Catalunya", 41.3870, 2.1700),
        Stop("aresta", "Aresta", 41.3922, 2.1700),
    )

    private val lines = mapOf(
        "L1" to Line("L1", "V15", "#D22E2E"),
        "L2" to Line("L2", "V17", "#044A9E"),
        "L3" to Line("L3", "EX1", "#101060"),
    )

    private fun secs(h: Int, m: Int): Int = h * 3600 + m * 60

    private val trips = listOf(
        Trip(
            "T1", "L1", "V15 Glories",
            listOf("sagrada", "gloria", "eixample", "cotxera", "catalunya"),
            listOf(secs(8, 0), secs(8, 1), secs(8, 2), secs(8, 14), secs(8, 16)),
            listOf(secs(8, 0), secs(8, 1), secs(8, 2), secs(8, 14), secs(8, 16)),
        ),
        Trip(
            "T2", "L2", "V17",
            listOf("sagrada", "eixample", "catalunya"),
            listOf(secs(8, 2), secs(8, 5), secs(8, 10)),
            listOf(secs(8, 2), secs(8, 5), secs(8, 10)),
        ),
        Trip(
            "T3", "L3", "EX1 Fortuna",
            listOf("eixample", "fortuna"),
            listOf(secs(8, 8), secs(8, 10)),
            listOf(secs(8, 8), secs(8, 10)),
        ),
    )

    private val timetable = Timetable(stops.associateBy { it.id }, lines, trips)
    private val router = Raptor()

    @Test
    fun sagradaToCatalunyaFindsRideWithBoardCheck() {
        val result = router.route(timetable, Query("sagrada", "catalunya", departAt = secs(8, 0)))
        assertTrue(result.isNotEmpty())
        val best = result.first()
        assertTrue(best.arrives > 28800)
        assertEquals(secs(8, 10), best.arrives)
        assertEquals(0, best.transfers)
        val finalLeg = best.legs.last()
        assertTrue(finalLeg is Leg.Ride)
        val ride = finalLeg as Leg.Ride
        assertEquals(listOf("sagrada", "eixample", "catalunya"), ride.stopIds)
        assertEquals("catalunya", ride.stopIds.last())
        assertTrue(ride.stopIds.contains("sagrada"))
        assertTrue(result.size >= 2)
    }

    @Test
    fun walkFinishesItineraryWhenDestinationHasNoDirectTrip() {
        val result = router.route(timetable, Query("sagrada", "aresta", departAt = secs(8, 0)))
        assertTrue(result.isNotEmpty())
        val best = result.first()
        assertTrue(best.arrives > 28800)
        val lastLeg = best.legs.last()
        assertTrue(lastLeg is Leg.Walk)
        val walk = lastLeg as Leg.Walk
        assertEquals("eixample", walk.fromStopId)
        assertEquals("aresta", walk.toStopId)
        assertTrue(walk.meters > 0.0 && walk.meters <= 500.0)
        val expectedWalkSecs = ceil(walk.meters / 1.2).toInt()
        assertEquals(walk.departs + expectedWalkSecs, walk.arrives)
        assertEquals(secs(8, 2), walk.departs)
        assertTrue(best.arrives < secs(8, 10))
        val prevLeg = best.legs[best.legs.size - 2]
        assertTrue(prevLeg is Leg.Ride)
        val ride = prevLeg as Leg.Ride
        assertEquals("eixample", ride.stopIds.last())
        assertTrue(ride.stopIds.first() == "sagrada")
        assertTrue(best.legs.dropLast(1).all { it is Leg.Ride })
    }

    @Test
    fun queryAfterAllTripsReturnsEmpty() {
        val result = router.route(timetable, Query("sagrada", "catalunya", departAt = 72000))
        assertTrue(result.isEmpty())
    }

    @Test
    fun maxTransfersLimitsTransfers() {
        val withTransfer = router.route(timetable, Query("gloria", "fortuna", departAt = secs(8, 0), maxTransfers = 1))
        assertTrue(withTransfer.isNotEmpty())
        assertEquals(secs(8, 10), withTransfer.first().arrives)
        assertEquals(1, withTransfer.first().transfers)
        assertTrue(withTransfer.first().legs.size == 2)
        val legs = withTransfer.first().legs
        assertTrue(legs[0] is Leg.Ride && legs[1] is Leg.Ride)
        val first = legs[0] as Leg.Ride
        assertEquals(listOf("gloria", "eixample"), first.stopIds)
        val second = legs[1] as Leg.Ride
        assertEquals(listOf("eixample", "fortuna"), second.stopIds)
        val noTransfer = router.route(timetable, Query("gloria", "fortuna", departAt = secs(8, 0), maxTransfers = 0))
        assertTrue(noTransfer.isEmpty())
    }
}
