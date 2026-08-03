// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TVRemoteCoreLogic",
    platforms: [.macOS(.v13)],
    products: [.library(name: "TVRemoteCoreLogic", targets: ["TVRemoteCoreLogic"])],
    targets: [
        .target(name: "TVRemoteCoreLogic"),
        .testTarget(name: "TVRemoteCoreLogicTests", dependencies: ["TVRemoteCoreLogic"]),
    ]
)
