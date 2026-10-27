package dev.hajnina.transit.core

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Raptor : Router {

    private data class Footpath(val to: String, val meters: Double, val secs: Int)

    override fun route(timetable: Timetable, query: Query): List<Itinerary> {
        if (timetable.stops[query.fromStopId] == null || timetable.stops[query.toStopId] == null) return emptyList()
        if (query.fromStopId == query.toStopId) return emptyList()
        val footpaths = buildFootpaths(timetable, query)
        val best = HashMap<String, Int>()
        val chains = HashMap<String, List<Leg>>()
        best[query.fromStopId] = query.departAt
        chains[query.fromStopId] = emptyList()
        val candidates = LinkedHashMap<String, Itinerary>()
        val maxRounds = (query.maxTransfers + 1).coerceAtLeast(1)
        for (round in 1..maxRounds) {
            val tau = HashMap(best)
            val tauChains = HashMap(chains)
            val roundBest = HashMap<String, Int>()
            val marked = LinkedHashSet<String>()
            for (trip in timetable.trips) {
                var boarded = -1
                for (i in trip.stopIds.indices) {
                    val stop = trip.stopIds[i]
                    if (boarded >= 0) {
                        val arr = trip.arrivals[i]
                        if (stop != query.fromStopId && arr < effective(best, roundBest, stop)) {
                            val chain = tauChains[trip.stopIds[boarded]].orEmpty() + Leg.Ride(
                                trip.id,
                                trip.lineId,
                                trip.headsign,
                                trip.stopIds.subList(boarded, i + 1).toList(),
                                trip.departures[boarded],
                                arr,
                            )
                            roundBest[stop] = arr
                            best[stop] = arr
                            chains[stop] = chain
                            marked.add(stop)
                            if (stop == query.toStopId) candidates[chain.toString()] = Itinerary(chain)
                        }
                    }
                    if (boarded < 0) {
                        val readyAt = tau[stop]
                        if (readyAt != null && trip.departures[i] >= readyAt) boarded = i
                    }
                }
            }
            if (marked.isEmpty()) break
            val queue = marked.toMutableList()
            var qi = 0
            while (qi < queue.size) {
                val src = queue[qi++]
                val srcArr = roundBest[src] ?: continue
                for (fp in footpaths[src].orEmpty()) {
                    if (fp.to == query.fromStopId) continue
                    var arr = srcArr + fp.secs
                    if (arr < effective(best, roundBest, fp.to)) {
                        val chain = chains[src].orEmpty() + Leg.Walk(src, fp.to, fp.meters, srcArr, arr)
                        roundBest[fp.to] = arr
                        best[fp.to] = arr
                        chains[fp.to] = chain
                        marked.add(fp.to)
                        queue.add(fp.to)
                        if (fp.to == query.toStopId) candidates[chain.toString()] = Itinerary(chain)
                    }
                }
            }
        }
        return candidates.values
            .distinct()
            .sortedWith(compareBy({ it.arrives }, { it.transfers }))
            .toList()
    }

    private fun effective(best: Map<String, Int>, roundBest: Map<String, Int>, stop: String): Int {
        val prior = best[stop]
        val current = roundBest[stop]
        return when {
            prior != null && current != null -> minOf(prior, current)
            prior != null -> prior
            current != null -> current
            else -> Int.MAX_VALUE
        }
    }

    private fun buildFootpaths(timetable: Timetable, query: Query): Map<String, List<Footpath>> {
        val stops = timetable.stops.values.toList()
        val builder = HashMap<String, MutableList<Footpath>>()
        for (a in stops.indices) {
            for (b in a + 1 until stops.size) {
                val sa = stops[a]
                val sb = stops[b]
                val meters = haversineMeters(sa, sb)
                if (meters <= query.maxWalkMeters) {
                    val secs = ceil(meters / query.walkSpeedMps).toInt()
                    builder.getOrPut(sa.id) { mutableListOf() }.add(Footpath(sb.id, meters, secs))
                    builder.getOrPut(sb.id) { mutableListOf() }.add(Footpath(sa.id, meters, secs))
                }
            }
        }
        return builder
    }

    private fun haversineMeters(a: Stop, b: Stop): Double {
        val radius = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * radius * asin(sqrt(s.coerceIn(0.0, 1.0)))
    }
}
