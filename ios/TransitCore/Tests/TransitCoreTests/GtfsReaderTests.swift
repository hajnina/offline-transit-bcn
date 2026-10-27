import XCTest
import ZIPFoundation
@testable import TransitCore

final class GtfsReaderTests: XCTestCase {
    private let day = ServiceDay(year: 2026, month: 5, day: 4)

    private func fixtureZip() throws -> Data {
        var files: [String: String] = [:]
        for name in ["stops", "routes", "trips", "stop_times", "calendar", "calendar_dates"] {
            files["\(name).txt"] = fixtureText(name)
        }
        return try zip(files)
    }
    func testReadsFixtureIntoTimetable() throws {
        let tt = try readGtfs(zip: fixtureZip(), serviceDay: day)
        XCTAssertEqual(Set(tt.stops.keys), Set(["S1", "S2", "S3"]))
        XCTAssertEqual(tt.stops["S1"]!.lat, 41.4036)
        XCTAssertEqual(Set(tt.lines.keys), Set(["L1"]))
        XCTAssertEqual(tt.lines["L1"], Line(id: "L1", shortName: "V15", colorHex: "#D22E2E"))
        XCTAssertEqual(tt.trips.map(\.id), ["T1", "T2"])
    }

    func testTripsCarryOrderedStopsAndUnwrappedTimes() throws {
        let tt = try readGtfs(zip: fixtureZip(), serviceDay: day)
        let t2 = tt.trips.first { $0.id == "T2" }!
        XCTAssertEqual(t2.stopIds, ["S1", "S2", "S3"])
        XCTAssertEqual(t2.arrivals, [25 * 3600 + 10 * 60, 25 * 3600 + 13 * 60, 25 * 3600 + 16 * 60])
        XCTAssertTrue(zip(t2.arrivals, t2.departures).allSatisfy { $0 <= $1 })
    }

    func testCalendarDatesExceptionActivatesServiceOnQueriedDay() throws {
        let tt = try readGtfs(zip: fixtureZip(), serviceDay: day)
        XCTAssertEqual(tt.trips.count, 2)
        let offDay = ServiceDay(year: 2026, month: 5, day: 3)
        XCTAssertEqual(try readGtfs(zip: fixtureZip(), serviceDay: offDay).trips.count, 0)
        let exceptionDay = ServiceDay(year: 2026, month: 5, day: 5)
        XCTAssertEqual(try readGtfs(zip: fixtureZip(), serviceDay: exceptionDay).trips.count, 0)
    }

    func testRemovedExceptionDropsService() throws {
        let z = try zip([
            "calendar.txt": fixtureText("calendar"),
            "calendar_dates.txt": "service_id,date,exception_type\nWD,20260504,2\n",
        ])
        XCTAssertEqual(try readGtfs(zip: z, serviceDay: day).trips.count, 0)
    }

    func testZipWithoutCalendarFilesUsesOnlyExceptions() throws {
        let z = try zip([
            "stops.txt": fixtureText("stops"),
            "routes.txt": fixtureText("routes"),
            "trips.txt": fixtureText("trips"),
            "stop_times.txt": fixtureText("stop_times"),
            "calendar_dates.txt": fixtureText("calendar_dates"),
        ])
        let tt = try readGtfs(zip: z, serviceDay: day)
        XCTAssertEqual(tt.trips.map(\.id), ["T1", "T2"])
        let laterDay = ServiceDay(year: 2026, month: 5, day: 6)
        XCTAssertEqual(try readGtfs(zip: z, serviceDay: laterDay).trips.count, 0)
    }
}
