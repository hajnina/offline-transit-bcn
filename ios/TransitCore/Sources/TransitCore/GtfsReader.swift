import Foundation
import ZIPFoundation

public struct ServiceDay: Equatable, Hashable, Comparable, Sendable {
    public let year: Int, month: Int, day: Int

    public init(year: Int, month: Int, day: Int) {
        (self.year, self.month, self.day) = (year, month, day)
    }
    public init?(yyyymmdd: Int) {
        guard yyyymmdd > 0 else { return nil }
        let y = yyyymmdd / 10000, m = (yyyymmdd / 100) % 100, d = yyyymmdd % 100
        guard y >= 1, y <= 9999, m >= 1, m <= 12, d >= 1, d <= Self.daysInMonth(y, m) else { return nil }
        self.init(year: y, month: m, day: d)
    }
    public var yyyymmdd: Int { year * 10000 + month * 100 + day }

    public var weekdayIndex: Int {
        guard (1...12).contains(month) else { return 0 }
        let offsets = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4]
        let y = month < 3 ? year - 1 : year
        let sundayBased = (y + y / 4 - y / 100 + y / 400 + offsets[month - 1] + day) % 7
        return (sundayBased + 6) % 7
    }

    public static func < (lhs: ServiceDay, rhs: ServiceDay) -> Bool { lhs.yyyymmdd < rhs.yyyymmdd }
    private static func daysInMonth(_ year: Int, _ month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        default: return isLeap(year) ? 29 : 28
        }
    }

    private static func isLeap(_ year: Int) -> Bool { (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 }
}

public enum GtfsError: Error, Equatable {
    case missingColumn(String)
    case malformedDate(String)
    case malformedTime(String)
}

public func readGtfs(zip data: Data, serviceDay: ServiceDay) throws -> Timetable {
    let files = try readZip(data)
    var rows: [String: [[String: String]]] = [:]
    for name in ["stops", "routes", "trips", "stop_times", "calendar", "calendar_dates"] {
        if let text = files[name + ".txt"] { rows[name] = parseCsv(text) }
    }

    var stops: [String: Stop] = [:]
    for row in rows["stops"] ?? [] {
        let id = try require(row, "stop_id")
        stops[id] = Stop(id: id, name: row["stop_name"] ?? "", lat: dbl(row, "stop_lat"), lon: dbl(row, "stop_lon"))
    }

    var lines: [String: Line] = [:]
    for row in rows["routes"] ?? [] {
        let id = try require(row, "route_id")
        let raw = row["route_color"] ?? ""
        var color = ""
        if !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            color = raw.hasPrefix("#") ? raw : "#" + raw
        }
        lines[id] = Line(id: id, shortName: row["route_short_name"] ?? "", colorHex: color)
    }

    let active = try servicesForDay(rows, serviceDay)
    var timesByTrip: [String: [[String: String]]] = [:]
    for st in rows["stop_times"] ?? [] {
        timesByTrip[try require(st, "trip_id"), default: []].append(st)
    }

    var trips: [Trip] = []
    for t in rows["trips"] ?? [] {
        guard active.contains(try require(t, "service_id")) else { continue }
        let id = try require(t, "trip_id"), lineId = try require(t, "route_id")
        let ordered = (timesByTrip[id] ?? []).enumerated()
            .sorted { lhs, rhs in
                let a = intOrZero(lhs.element["stop_sequence"]), b = intOrZero(rhs.element["stop_sequence"])
                return a != b ? a < b : lhs.offset < rhs.offset
            }
            .map(\.element)
        var stopIds: [String] = [], arrivals: [Secs] = [], departures: [Secs] = []
        for st in ordered {
            stopIds.append(try require(st, "stop_id"))
            arrivals.append(try timeSec(st, "arrival_time"))
            departures.append(try timeSec(st, "departure_time"))
        }
        guard stopIds.count >= 2 else { continue }
        trips.append(Trip(id: id, lineId: lineId, headsign: t["trip_headsign"] ?? "", stopIds: stopIds,
                          arrivals: arrivals, departures: departures))
    }
    return Timetable(stops: stops, lines: lines, trips: trips)
}

private func readZip(_ data: Data) throws -> [String: String] {
    let archive = try Archive(data: data, accessMode: .read)
    var files: [String: String] = [:]
    for entry in archive where entry.type == .file {
        var bytes = Data()
        _ = try archive.extract(entry, skipCRC32: true) { bytes.append($0) }
        let name = entry.path.split(separator: "/").last.map(String.init) ?? entry.path
        files[name] = String(decoding: bytes, as: UTF8.self)
    }
    return files
}

private func servicesForDay(_ rows: [String: [[String: String]]], _ day: ServiceDay) throws -> Set<String> {
    let flags = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    var active = Set<String>()
    for c in rows["calendar"] ?? [] {
        let start = try parseDate(try require(c, "start_date"))
        let end = try parseDate(try require(c, "end_date"))
        if day >= start, day <= end, c[flags[day.weekdayIndex]] == "1" {
            active.insert(try require(c, "service_id"))
        }
    }
    for x in rows["calendar_dates"] ?? [] {
        guard try parseDate(try require(x, "date")) == day else { continue }
        switch x["exception_type"] {
        case "1": active.insert(try require(x, "service_id"))
        case "2": active.remove(try require(x, "service_id"))
        default: break
        }
    }
    return active
}

private func parseCsv(_ text: String) -> [[String: String]] {
    var clean = text
    if clean.hasPrefix("\u{FEFF}") { clean.removeFirst() }
    clean = clean.replacingOccurrences(of: "\r\n", with: "\n")
    let lines = clean.split(separator: "\n", omittingEmptySubsequences: false).filter { !$0.isEmpty }
    guard let first = lines.first else { return [] }
    let header = splitLine(String(first))
    return lines.dropFirst()
        .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        .map { row in
            let cells = splitLine(String(row))
            var record: [String: String] = [:]
            for (i, h) in header.enumerated() { record[h] = i < cells.count ? cells[i] : "" }
            return record
        }
}

private func splitLine(_ line: String) -> [String] {
    let chars = Array(line)
    var cells: [String] = [], sb = "", quoted = false, i = 0
    while i < chars.count {
        let ch = chars[i]
        if ch == "\"" {
            if quoted, i + 1 < chars.count, chars[i + 1] == "\"" { sb.append("\""); i += 1 } else { quoted.toggle() }
        } else if ch == "," {
            if quoted { sb.append(ch) } else { cells.append(sb); sb = "" }
        } else {
            sb.append(ch)
        }
        i += 1
    }
    cells.append(sb)
    return cells
}

private func parseDate(_ raw: String) throws -> ServiceDay {
    guard let value = Int(raw), let day = ServiceDay(yyyymmdd: value) else { throw GtfsError.malformedDate(raw) }
    return day
}

private func intOrZero(_ raw: String?) -> Int { Int(raw ?? "") ?? 0 }
private func timeSec(_ row: [String: String], _ field: String) throws -> Int {
    guard let raw = row[field], !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return 0 }
    let parts = raw.split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 3 else { throw GtfsError.malformedTime(raw) }
    let nums = parts.map { Int($0.trimmingCharacters(in: .whitespaces)) }
    guard let h = nums[0], let m = nums[1], let s = nums[2] else { throw GtfsError.malformedTime(raw) }
    return h * 3600 + m * 60 + s
}

private func require(_ row: [String: String], _ key: String) throws -> String {
    guard let value = row[key] else { throw GtfsError.missingColumn(key) }
    return value
}
private func dbl(_ row: [String: String], _ key: String) -> Double {
    guard let raw = row[key], !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return 0.0 }
    return Double(raw) ?? 0.0
}
