import XCTest
@testable import TransitCore

final class ContractsTests: XCTestCase {
    func testItineraryDerivesTimesAndTransfers() {
        let a = Ride(tripId: "t1", lineId: "L1", headsign: "Fondo", stopIds: ["A", "B"], departs: 100, arrives: 200)
        let w = Walk(fromStopId: "B", toStopId: "C", meters: 120.0, departs: 200, arrives: 300)
        let b = Ride(tripId: "t2", lineId: "L3", headsign: "Zona", stopIds: ["C", "D"], departs: 360, arrives: 500)
        let it = Itinerary(legs: [.ride(a), .walk(w), .ride(b)])
        XCTAssertEqual(it.departs, 100)
        XCTAssertEqual(it.arrives, 500)
        XCTAssertEqual(it.transfers, 1)
    }

    func testFakeRouterReturnsCanned() {
        let it = Itinerary(legs: [.walk(Walk(fromStopId: "A", toStopId: "B", meters: 50.0, departs: 0, arrives: 42))])
        let tt = Timetable(stops: [:], lines: [:], trips: [])
        XCTAssertEqual(FakeRouter(canned: [it]).route(timetable: tt, query: Query(fromStopId: "A", toStopId: "B", departAt: 0)), [it])
    }
}
