/// Seconds since local midnight of the service day.
///
/// May exceed 86400 to represent GTFS times such as "25:10:00".
public typealias Secs = Int

public struct Stop: Equatable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let lat: Double
    public let lon: Double

    public init(id: String, name: String, lat: Double, lon: Double) {
        self.id = id
        self.name = name
        self.lat = lat
        self.lon = lon
    }
}

/// One scheduled run of a line.
public struct Trip: Equatable, Hashable, Sendable {
    public let id: String
    public let lineId: String
    public let headsign: String
    public let stopIds: [String]
    /// Parallel to `stopIds`; `arrivals[i] <= departures[i]`.
    public let arrivals: [Secs]
    /// Parallel to `stopIds`; `arrivals[i] <= departures[i]`.
    public let departures: [Secs]

    public init(
        id: String,
        lineId: String,
        headsign: String,
        stopIds: [String],
        arrivals: [Secs],
        departures: [Secs]
    ) {
        self.id = id
        self.lineId = lineId
        self.headsign = headsign
        self.stopIds = stopIds
        self.arrivals = arrivals
        self.departures = departures
    }
}

public struct Line: Equatable, Hashable, Sendable {
    public let id: String
    public let shortName: String
    public let colorHex: String

    public init(id: String, shortName: String, colorHex: String) {
        self.id = id
        self.shortName = shortName
        self.colorHex = colorHex
    }
}

/// Everything the router needs for ONE service day (calendar already resolved by the data pack).
public struct Timetable: Equatable, Hashable, Sendable {
    public let stops: [String: Stop]
    public let lines: [String: Line]
    public let trips: [Trip]

    public init(stops: [String: Stop], lines: [String: Line], trips: [Trip]) {
        self.stops = stops
        self.lines = lines
        self.trips = trips
    }
}

public struct Query: Equatable, Hashable, Sendable {
    public let fromStopId: String
    public let toStopId: String
    public let departAt: Secs
    public let maxTransfers: Int
    public let walkSpeedMps: Double
    public let maxWalkMeters: Double

    public init(
        fromStopId: String,
        toStopId: String,
        departAt: Secs,
        maxTransfers: Int = 4,
        walkSpeedMps: Double = 1.2,
        maxWalkMeters: Double = 500.0
    ) {
        self.fromStopId = fromStopId
        self.toStopId = toStopId
        self.departAt = departAt
        self.maxTransfers = maxTransfers
        self.walkSpeedMps = walkSpeedMps
        self.maxWalkMeters = maxWalkMeters
    }
}

public struct Ride: Equatable, Hashable, Sendable {
    public let tripId: String
    public let lineId: String
    public let headsign: String
    /// Every stop of the route segment, boarding stop first, alighting stop last.
    public let stopIds: [String]
    public let departs: Secs
    public let arrives: Secs

    public init(
        tripId: String,
        lineId: String,
        headsign: String,
        stopIds: [String],
        departs: Secs,
        arrives: Secs
    ) {
        self.tripId = tripId
        self.lineId = lineId
        self.headsign = headsign
        self.stopIds = stopIds
        self.departs = departs
        self.arrives = arrives
    }
}

public struct Walk: Equatable, Hashable, Sendable {
    public let fromStopId: String
    public let toStopId: String
    public let meters: Double
    public let departs: Secs
    public let arrives: Secs

    public init(
        fromStopId: String,
        toStopId: String,
        meters: Double,
        departs: Secs,
        arrives: Secs
    ) {
        self.fromStopId = fromStopId
        self.toStopId = toStopId
        self.meters = meters
        self.departs = departs
        self.arrives = arrives
    }
}

public enum Leg: Equatable, Hashable, Sendable {
    case ride(Ride)
    case walk(Walk)
}

public struct Itinerary: Equatable, Hashable, Sendable {
    public let legs: [Leg]
    /// Time of the first leg, ride or walk.
    public let departs: Secs
    /// Time of the last leg.
    public let arrives: Secs
    /// Count of ride legs minus one.
    public let transfers: Int

    public init(legs: [Leg]) {
        self.legs = legs
        self.departs = legs.first.map(Self.departs(of:)) ?? 0
        self.arrives = legs.last.map(Self.arrives(of:)) ?? 0
        let rides = legs.reduce(into: 0) { count, leg in
            if case .ride = leg { count += 1 }
        }
        self.transfers = max(0, rides - 1)
    }

    private static func departs(of leg: Leg) -> Secs {
        switch leg {
        case .ride(let ride): return ride.departs
        case .walk(let walk): return walk.departs
        }
    }

    private static func arrives(of leg: Leg) -> Secs {
        switch leg {
        case .ride(let ride): return ride.arrives
        case .walk(let walk): return walk.arrives
        }
    }
}

public protocol Router {
    func route(timetable: Timetable, query: Query) -> [Itinerary]
}

public struct FakeRouter: Router {
    public let canned: [Itinerary]

    public init(canned: [Itinerary]) {
        self.canned = canned
    }

    public func route(timetable: Timetable, query: Query) -> [Itinerary] {
        canned
    }
}
