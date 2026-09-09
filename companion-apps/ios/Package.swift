// swift-tools-version: 6.0
import PackageDescription
let package = Package(
    name: "YanoCompanion", platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "CompanionCore", targets: ["CompanionCore"]),
               .executable(name: "companion-exchange", targets: ["CompanionExchange"])],
    targets: [.target(name: "CompanionCore"),
              .executableTarget(name: "CompanionExchange", dependencies: ["CompanionCore"]),
              .testTarget(name: "CompanionCoreTests", dependencies: ["CompanionCore"], resources: [.copy("Fixtures")])]
)
