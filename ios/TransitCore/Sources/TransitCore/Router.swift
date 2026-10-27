import Foundation

/// Fastest-first journey planner over one service day of a small offline timetable.
///
/// Search model (RAPTOR-lite, fixture scale):
///  - Moves are pair walks between any two stops within `query.maxWalkMeters`
///    (both directions), or forward rides on trips: board at stop i when
///    `trip.departures[i] >= arrival time at i`, alight at a later stop
///    `j > i`, arriving exactly `trip.arrivals[j]`.
///  - Walking time is `Int(haversineMeters / query.walkSpeedMps)` seconds; a
///    walk departs at the moment its from-stop is reached. Walk legs chain hop
///    by hop (never merged ahead of time), so multi-hop walks between nearby
///    stops are valid.
///  - At most `query.maxTransfers + 1` ride legs per itinerary; never revisit a
///    stop on one path (dominance pruning on arrival time + ride count).
///  - Arrival at the destination happens either by alighting a ride exactly at
///    the destination stop or by a direct walk from the origin stop (footpath
///    hops therefore never land on the destination, keeping the pure walk
///    itinerary competitive for close pairs).
///  - Origin == destination yields [], as do unknown stop ids.
///  - Results are ranked (arrival asc, transfers asc, departure asc, leg count
///    asc) with exact duplicates removed.
public struct RealRouter: Router {
    public init() {}

    public func route(timetable: Timetable, query: Query) -> [Itinerary] {
        let origin = query.fromStopId
        let destination = query.toStopId
        guard origin != destination,
              timetable.stops[origin] != nil,
              timetable.stops[destination] != nil else {
            return []
        }

        let maxRides = query.maxTransfers + 1
        let plan = LegPlan(
            timetable: timetable,
            origin: origin,
            destination: destination,
            walkSpeedMps: query.walkSpeedMps,
            maxWalkMeters: query.maxWalkMeters
        )

        var bestArrival: Secs?
        var results = [Itinerary]()
        var dominance = [String: [Dom]]()
        var onPath: Set<String> = []

        func append(_ itinerary: Itinerary) {
            if let best = bestArrival, itinerary.arrives > best { return }
            results.append(itinerary)
            if bestArrival == nil || itinerary.arrives < bestArrival! {
                bestArrival = itinerary.arrives
            }
        }

        func expand(stop: String, at time: Secs, legs: [Leg], ridesUsed: Int) {
            if let best = bestArrival, time > best { return }
            if dominated(dominance[stop], by: time, rides: ridesUsed) { return }
            remember(stop, time: time, rides: ridesUsed, in: &dominance)
            onPath.insert(stop)
            defer { onPath.remove(stop) }

            if ridesUsed < maxRides, let terminal = plan.terminalRides[stop] {
                var index = terminal.firstIndex { $0.departs >= time } ?? terminal.count
                while index < terminal.count {
                    append(Itinerary(legs: legs + [.ride(terminal[index])]))
                    index += 1
                }
            }
            if stop == origin, let direct = plan.originDirectWalk {
                let walk = Walk(
                    fromStopId: stop,
                    toStopId: destination,
                    meters: direct.meters,
                    departs: time,
                    arrives: time + direct.seconds
                )
                append(Itinerary(legs: legs + [.walk(walk)]))
            }
            if ridesUsed < maxRides, let rides = plan.ridesFrom[stop] {
                var index = rides.firstIndex { $0.departs >= time } ?? rides.count
                while index < rides.count {
                    let ride = rides[index]
                    index += 1
                    let alight = ride.stopIds.last ?? stop
                    if alight == destination || onPath.contains(alight) { continue }
                    expand(stop: alight, at: ride.arrives, legs: legs + [.ride(ride)], ridesUsed: ridesUsed + 1)
                }
            }
            for edge in plan.walksFrom[stop] ?? [] {
                if edge.to == destination || onPath.contains(edge.to) { continue }
                let walk = Walk(
                    fromStopId: stop,
                    toStopId: edge.to,
                    meters: edge.meters,
                    departs: time,
                    arrives: time + edge.seconds
                )
                expand(stop: edge.to, at: walk.arrives, legs: legs + [.walk(walk)], ridesUsed: ridesUsed)
            }
        }

        expand(stop: origin, at: query.departAt, legs: [], ridesUsed: 0)
        guard !results.isEmpty else { return [] }

        let ranked = results.sorted {
            if $0.arrives != $1.arrives { return $0.arrives < $1.arrives }
            if $0.transfers != $1.transfers { return $0.transfers < $1.transfers }
            if $0.legs.count != $1.legs.count { return $0.legs.count < $1.legs.count }
            return $0.departs < $1.departs
        }
        var seen = Set<Itinerary>()
        var unique = [Itinerary]()
        for itinerary in ranked where seen.insert(itinerary).inserted {
            unique.append(itinerary)
        }
        return unique
    }

    private struct Dom {
        var time: Secs
        var rides: Int
    }

    private func dominated(_ entries: [Dom]?, by time: Secs, rides: Int) -> Bool {
        entries?.contains { $0.time <= time && $0.rides <= rides } ?? false
    }

    private func remember(_ stop: String, time: Secs, rides: Int, in storage: inout [String: [Dom]]) {
        var entries = storage[stop] ?? []
        entries.removeAll { $0.time >= time && $0.rides >= rides }
        entries.append(Dom(time: time, rides: rides))
        storage[stop] = entries
    }
}

private struct FootEdge {
    var to: String
    var meters: Double
    var seconds: Secs
}

private struct LegPlan {
    var originDirectWalk: FootEdge?
    var terminalRides = [String: [Ride]]()
    var ridesFrom = [String: [Ride]]()
    var walksFrom = [String: [FootEdge]]()

    init(
        timetable: Timetable,
        origin: String,
        destination: String,
        walkSpeedMps: Double,
        maxWalkMeters: Double
    ) {
        originDirectWalk = LegPlan.footEdge(
            from: timetable.stops[origin],
            to: timetable.stops[destination],
            walkSpeedMps: walkSpeedMps,
            maxWalkMeters: maxWalkMeters
        )

        var terminals = [String: [Ride]]()
        var onward = [String: [Ride]]()
        for trip in timetable.trips {
            let stops = trip.stopIds
            for i in 0 ..< stops.count where stops[i] != destination {
                for j in (i + 1) ..< stops.count {
                    let ride = Ride(
                        tripId: trip.id,
                        lineId: trip.lineId,
                        headsign: trip.headsign,
                        stopIds: Array(stops[i ... j]),
                        departs: trip.departures[i],
                        arrives: trip.arrivals[j]
                    )
                    if stops[j] == destination {
                        terminals[stops[i], default: []].append(ride)
                    } else {
                        onward[stops[i], default: []].append(ride)
                    }
                }
            }
        }
        terminalRides = terminals.mapValues { $0.sorted { LegPlan.rideOrder($0, $1) } }
        ridesFrom = onward.mapValues { $0.sorted { LegPlan.rideOrder($0, $1) } }

        guard walkSpeedMps > 0 else { return }
        var edges = [String: [FootEdge]]()
        let ordered = timetable.stops.values.sorted { $0.id < $1.id }
        for source in ordered {
            for target in ordered where target.id != destination && target.id != source.id {
                guard let edge = LegPlan.footEdge(
                    from: source,
                    to: target,
                    walkSpeedMps: walkSpeedMps,
                    maxWalkMeters: maxWalkMeters
                ) else { continue }
                edges[source.id, default: []].append(edge)
            }
        }
        walksFrom = edges.mapValues { $0.sorted { ($0.seconds, $0.to) < ($1.seconds, $1.to) } }
    }

    static func footEdge(from: Stop?, to: Stop?, walkSpeedMps: Double, maxWalkMeters: Double) -> FootEdge? {
        guard let source = from, let target = to, walkSpeedMps > 0 else { return nil }
        let meters = haversineMeters(from: source, to: target)
        guard meters <= maxWalkMeters else { return nil }
        return FootEdge(to: target.id, meters: meters, seconds: Int(meters / walkSpeedMps))
    }

    static func haversineMeters(from: Stop, to: Stop) -> Double {
        let radius = 6_371_000.0
        func rad(_ deg: Double) -> Double { deg * Double.pi / 180 }
        let dLat = rad(to.lat - from.lat)
        let dLon = rad(to.lon - from.lon)
        let sinLat = sin(dLat / 2)
        let sinLon = sin(dLon / 2)
        let a = sinLat * sinLat + cos(rad(from.lat)) * cos(rad(to.lat)) * sinLon * sinLon
        return 2 * radius * asin(min(1, a.squareRoot()))
    }

    static func rideOrder(_ lhs: Ride, _ rhs: Ride) -> Bool {
        if lhs.departs != rhs.departs { return lhs.departs < rhs.departs }
        if lhs.arrives != rhs.arrives { return lhs.arrives < rhs.arrives }
        return lhs.tripId < rhs.tripId
    }
}


