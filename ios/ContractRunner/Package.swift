// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "ContractRunner",
    platforms: [
        .iOS(.v16),
        .tvOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "ContractRunner", targets: ["ContractRunner"])
    ],
    targets: [
        .target(name: "ContractRunner"),
        .testTarget(name: "ContractRunnerTests", dependencies: ["ContractRunner"])
    ]
)
