// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TypeBridgeMac",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "TypeBridgeMac", targets: ["TypeBridgeMac"])
    ],
    dependencies: [
        .package(url: "https://github.com/swhitty/FlyingFox.git", from: "0.27.1")
    ],
    targets: [
        .target(
            name: "TypeBridgeCore",
            dependencies: [
                .product(name: "FlyingFox", package: "FlyingFox")
            ],
            path: "Sources/TypeBridgeCore",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "TypeBridgeMac",
            dependencies: ["TypeBridgeCore"],
            path: "Sources/TypeBridgeMac",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "TypeBridgeCoreTests",
            dependencies: ["TypeBridgeCore"],
            path: "Tests/TypeBridgeCoreTests",
            swiftSettings: [.swiftLanguageMode(.v5)]
        )
    ]
)
