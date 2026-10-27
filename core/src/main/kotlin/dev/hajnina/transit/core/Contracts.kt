package dev.hajnina.transit.core

/** Seconds since local midnight of the service day; may exceed 86400 (GTFS "25:10:00"). */
typealias Secs = Int

data class Stop(val id: String, val name: String, val lat: Double, val lon: Double)

/** One scheduled run of a line. stops/times are parallel arrays; arrivals[i] <= departures[i]. */
data class Trip(
    val id: String,
    val lineId: String,
    val headsign: String,
    val stopIds: List<String>,
    val arrivals: List<Secs>,
    val departures: List<Secs>,
)

data class Line(val id: String, val shortName: String, val colorHex: String)

/** Everything the router needs for ONE service day (calendar already resolved by the data pack). */
data class Timetable(
    val stops: Map<String, Stop>,
    val lines: Map<String, Line>,
    val trips: List<Trip>,
)

data class Query(
    val fromStopId: String,
    val toStopId: String,
    val departAt: Secs,
    val maxTransfers: Int = 4,
    val walkSpeedMps: Double = 1.2,
    val maxWalkMeters: Double = 500.0,
)

sealed interface Leg {
    data class Ride(
        val tripId: String,
        val lineId: String,
        val headsign: String,
        /** Every stop of the leg, boarding stop first, alighting stop last (board-check rule). */
        val stopIds: List<String>,
        val departs: Secs,
        val arrives: Secs,
    ) : Leg

    data class Walk(val fromStopId: String, val toStopId: String, val meters: Double, val departs: Secs, val arrives: Secs) : Leg
}

data class Itinerary(val legs: List<Leg>) {
    val departs: Secs get() = legs.first().let { if (it is Leg.Ride) it.departs else (it as Leg.Walk).departs }
    val arrives: Secs get() = legs.last().let { if (it is Leg.Ride) it.arrives else (it as Leg.Walk).arrives }
    val transfers: Int get() = legs.count { it is Leg.Ride } - 1
}

/** The single seam between the UI and the algorithm. Pure Kotlin, no Android. Results ranked best first. */
interface Router {
    fun route(timetable: Timetable, query: Query): List<Itinerary>
}

/** Test double for UI work: always returns one canned walk-only itinerary. */
class FakeRouter(private val canned: List<Itinerary>) : Router {
    override fun route(timetable: Timetable, query: Query): List<Itinerary> = canned
}
