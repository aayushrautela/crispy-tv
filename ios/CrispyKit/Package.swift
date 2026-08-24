// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CrispyKit",
    platforms: [
        .iOS(.v17),
        .tvOS(.v17),
        .macOS(.v13)
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
        )
    ]
)
