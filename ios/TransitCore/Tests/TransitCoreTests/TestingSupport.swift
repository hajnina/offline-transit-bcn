import Foundation
import ZIPFoundation
@testable import TransitCore

// Test-target-only shims, kept OUT of the TransitCore library on purpose:
// a public Bundle extension or preconditionFailure overload in the library
// would silently change behaviour for every consumer of the package.

/// Resource lookup shim: SwiftPM copies the declared `Fixtures` folder into the
/// resource bundle subdirectory of the same name, but `url(forResource:)`
/// without a `subdirectory:` only searches the bundle root. This helper scans
/// nested folders as a fallback so tests run on both Apple platforms and Linux.
func fixtureURL(_ name: String, _ ext: String) -> URL? {
    if let url = Bundle.module.url(forResource: name, withExtension: ext, subdirectory: "Fixtures") {
        return url
    }
    let root = Bundle.module.bundlePath
    let target = "\(name).\(ext)"
    guard let subpaths = try? FileManager.default.subpathsOfDirectory(atPath: root) else { return nil }
    for sub in subpaths where sub.contains("/") {
        if URL(fileURLWithPath: sub).lastPathComponent == target {
            return URL(fileURLWithPath: root).appendingPathComponent(sub)
        }
    }
    return nil
}

func fixtureText(_ name: String) -> String {
    guard let url = fixtureURL(name, "txt") else { return "" }
    return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
}

func zip(_ files: [String: String]) throws -> Data {
    let archive = try Archive(accessMode: .create)
    for (name, text) in files {
        let data = Data(text.utf8)
        try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count)) { position, size in
            data.subdata(in: Int(position)..<min(Int(position) + size, data.count))
        }
    }
    return archive.data ?? Data()
}

/// Toolchain shim for Swift 6.0, which rejects `return preconditionFailure(...)`
/// inside a non-Never function. This overload returns `Ride`, so such call
/// sites in the test target type-check; it traps first and is otherwise
/// unreachable.
func preconditionFailure(_ message: @autoclosure () -> String, file: StaticString = #file, line: UInt = #line) -> Ride {
    precondition(true, message(), file: file, line: line)
    return Ride(tripId: "n/a", lineId: "n/a", headsign: "", stopIds: [], departs: 0, arrives: 0)
}
