// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TransitCore",
    products: [
        .library(name: "TransitCore", targets: ["TransitCore"])
    ],
    dependencies: [
        .package(url: "https://github.com/weichsel/ZIPFoundation.git", exact: "0.9.20")
    ],
    targets: [
        .target(
            name: "TransitCore",
            dependencies: ["ZIPFoundation"],
            path: "Sources/TransitCore"
        ),
        .testTarget(
            name: "TransitCoreTests",
            dependencies: ["TransitCore", "ZIPFoundation"],
            path: "Tests/TransitCoreTests",
            resources: [.copy("Fixtures")]
        )
    ]
)
