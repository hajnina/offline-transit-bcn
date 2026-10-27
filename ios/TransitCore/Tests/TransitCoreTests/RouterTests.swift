import Foundation
import XCTest
@testable import TransitCore

final class RouterTests: XCTestCase {
    private var fixtureTimetable: Timetable!

    override func setUp() {
        super.setUp()
        let data = try! Bundle.module.url(forResource: "small-timetable", withExtension: "json").map { try Data(contentsOf: $0) }!
        fixtureTimetable = RouterTests.decode(data)
    }

    static func decode(_ data: Data) -> Timetable {
        struct RawTimetable: Decodable { let stops: [String: RawStop]; let lines: [String: RawLine]; let trips: [RawTrip] }
        struct RawStop: Decodable { let id: String; let name: String; let lat: Double; let lon: Double }
        struct RawLine: Decodable { let id: String; let shortName: String; let colorHex: String }
        struct RawTrip: Decodable { let id: String; let lineId: String; let headsign: String; let stopIds: [String]; let arrivals: [Int]; let departures: [Int] }
        let raw = try! JSONDecoder().decode(RawTimetable.self, from: data)
        let stops = raw.stops.mapValues { Stop(id: $0.id, name: $0.name, lat: $0.lat, lon: $0.lon) }
        let lines = raw.lines.mapValues { Line(id: $0.id, shortName: $0.shortName, colorHex: $0.colorHex) }
        let trips = raw.trips.map { Trip(id: $0.id, lineId: $0.lineId, headsign: $0.headsign, stopIds: $0.stopIds, arrivals: $0.arrivals, departures: $0.departures) }
        return Timetable(stops: stops, lines: lines, trips: trips)
    }

    private func ride(_ legs: [Leg], _ index: Int) -> Ride {
        guard case .ride(let ride) = legs[index] else { return preconditionFailure("expected ride at \(index)") }
        return ride
    }

    func testDirectRideIsBest() {
        let r = RealRouter().route(timetable: fixtureTimetable, query: Query(fromStopId: "A", toStopId: "C", departAt: 0))
        XCTAssertFalse(r.isEmpty)
        let best = r[0]
        XCTAssertEqual(best.legs.count, 1)
        let rideLeg = ride(best.legs, 0)
        XCTAssertEqual(rideLeg.tripId, "M1a")
        XCTAssertEqual(rideLeg.departs, 100)
        XCTAssertEqual(rideLeg.arrives, 140)
        XCTAssertEqual(best.transfers, 0)
    }

    func testWalkBeatsRideWhenFaster() {
        let r = RealRouter().route(timetable: fixtureTimetable, query: Query(fromStopId: "C", toStopId: "A", departAt: 0))
        XCTAssertFalse(r.isEmpty)
        let best = r[0]
        XCTAssertEqual(best.legs.count, 1)
        guard case .walk(let walk) = best.legs[0] else { XCTFail("expected walk best: \(best)"); return }
        XCTAssertEqual(best.departs, 0)
        XCTAssertLessThanOrEqual(best.arrives, 270)
        XCTAssertGreaterThan(walk.meters, 250)
        XCTAssertLessThan(walk.meters, 330)
        XCTAssertEqual(walk.fromStopId, "C")
        XCTAssertEqual(walk.toStopId, "A")
    }

    func testTransferViaWalk() {
        let r = RealRouter().route(timetable: fixtureTimetable, query: Query(fromStopId: "B", toStopId: "E", departAt: 0))
        XCTAssertFalse(r.isEmpty)
        let best = r[0]
        XCTAssertEqual(best.arrives, 1300)
        XCTAssertEqual(best.transfers, 0)
        let rideLegs = best.legs.compactMap { leg -> Ride? in if case .ride(let rd) = leg { return rd }; return nil }
        XCTAssertEqual(rideLegs.count, 1)
        XCTAssertEqual(rideLegs.first?.tripId, "M3b")
        XCTAssertTrue(rideLegs.first?.stopIds.contains("E") ?? false)
    }

    func testSingleWalkBest() {
        let r = RealRouter().route(timetable: fixtureTimetable, query: Query(fromStopId: "A", toStopId: "D", departAt: 0))
        XCTAssertEqual(r.first?.legs.count, 1)
        guard r.first?.legs.count == 1, case .walk(let walk) = r.first!.legs[0] else { XCTFail("expected single walk: \(String(describing: r.first))"); return }
        XCTAssertGreaterThan(walk.meters, 440)
        XCTAssertLessThan(walk.meters, 470)
        XCTAssertEqual(r.first?.transfers, 0)
        XCTAssertLessThanOrEqual(r.first?.arrives ?? 9999, 390)
    }

    func testNoBridgeToEcho() {
        XCTAssertTrue(RealRouter().route(timetable: fixtureTimetable, query: Query(fromStopId: "E", toStopId: "A", departAt: 0)).isEmpty)
    }

    func testUnknownStopsReturnEmpty() {
        XCTAssertTrue(RealRouter().route(timetable: fixtureTimetable, query: Query(fromStopId: "ZZZ", toStopId: "A", departAt: 0)).isEmpty)
        XCTAssertTrue(RealRouter().route(timetable: fixtureTimetable, query: Query(fromStopId: "A", toStopId: "ZZZ", departAt: 0)).isEmpty)
    }
}
