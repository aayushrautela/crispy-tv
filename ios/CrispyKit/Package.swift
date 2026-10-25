// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CrispyKit",
    platforms: [
        .iOS(.v17),
        .tvOS(.v17),
        // macOS 14 is the floor `Observation` (the `@Observable` macro) sets.
        // The macOS platform exists so the suite can run in CI; the apps
        // themselves are iOS and tvOS.
        .macOS(.v14)
    ],
    products: [
        .library(name: "CrispyKit", targets: ["CrispyKit"])
    ],
    dependencies: [
        .package(path: "../ContractRunner")
    ],
    targets: [
        .target(
            name: "CrispyKit",
            dependencies: [
                .product(name: "ContractRunner", package: "ContractRunner")
            ]
        ),
        .testTarget(
            name: "CrispyKitTests",
            dependencies: [
                "CrispyKit",
                .product(name: "ContractRunner", package: "ContractRunner")
            ]
        )
    ]
)
