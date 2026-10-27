// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TransitCore",
    products: [
        .library(name: "TransitCore", targets: ["TransitCore"])
    ],
    targets: [
        .target(name: "TransitCore", path: "Sources/TransitCore"),
        .testTarget(name: "TransitCoreTests", dependencies: ["TransitCore"], path: "Tests/TransitCoreTests")
    ]
)
