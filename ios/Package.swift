// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MeshGramCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "MeshGramCore", targets: ["MeshGramCore"])],
    targets: [
        .target(name: "MeshGramCore"),
        .testTarget(name: "MeshGramCoreTests", dependencies: ["MeshGramCore"], resources: [.copy("Fixtures")])
    ]
)
