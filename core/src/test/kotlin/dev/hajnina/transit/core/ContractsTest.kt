package dev.hajnina.transit.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ContractsTest {
    @Test
    fun itineraryDerivesTimesAndTransfers() {
        val a = Leg.Ride("t1", "L1", "Fondo", listOf("A", "B"), 100, 200)
        val w = Leg.Walk("B", "C", 120.0, 200, 300)
        val b = Leg.Ride("t2", "L3", "Zona", listOf("C", "D"), 360, 500)
        val it = Itinerary(listOf(a, w, b))
        assertEquals(100, it.departs)
        assertEquals(500, it.arrives)
        assertEquals(1, it.transfers)
    }

    @Test
    fun fakeRouterReturnsCanned() {
        val it = Itinerary(listOf(Leg.Walk("A", "B", 50.0, 0, 42)))
        val tt = Timetable(emptyMap(), emptyMap(), emptyList())
        assertEquals(listOf(it), FakeRouter(listOf(it)).route(tt, Query("A", "B", 0)))
    }
}
