// swift-tools-version:5.9
// iOS XCTest 运行级门禁的 SwiftPM 清单（仅 CI 与 macOS 开发者使用；产品构建仍以 CI 链接门禁为准）。
// 静态库由 CI 构建期填充到 ios-controller/VendorLib/（见 .gitignore 与 ci.yml 的 XCTest 步骤）。
import PackageDescription

let package = Package(
    name: "TVRemoteControllerIOS",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "IOSController", targets: ["IOSController"]),
    ],
    targets: [
        // Rust 核心（uniffi Swift 绑定；静态库为 aarch64-apple-ios-sim）
        .systemLibrary(
            name: "TvremoteCoreFFI",
            path: "core-rs/bindings/swift",
            linkerSettings: [
                .linkedLibrary("tvremote_core"),
                .unsafeFlags(["-L", "core-rs/target/aarch64-apple-ios-sim/release"]),
            ]
        ),
        // Zig 协议核心（aarch64-ios-simulator 静态库 + mbedTLS 三库；头文件直接引用仓库源目录）
        .systemLibrary(
            name: "TvRemoteCoreZig",
            path: "ios-controller/VendorModules",
            cSettings: [.unsafeFlags(["-I", "windows-controller/include"])],
            linkerSettings: [
                .linkedLibrary("tv_remote_core"),
                .linkedLibrary("mbedtls"),
                .linkedLibrary("mbedx509"),
                .linkedLibrary("mbedcrypto"),
                .unsafeFlags(["-L", "ios-controller/VendorLib"]),
            ]
        ),
        .target(
            name: "IOSController",
            path: "ios-controller/Sources",
            sources: ["ControllerApp.swift", "KeychainCredentialStore.swift", "WsDebugClient.swift", "RustBindings/tvremote_core.swift"],
            dependencies: ["TvremoteCoreFFI", "TvRemoteCoreZig"],
            linkerSettings: [.unsafeFlags(["-L", "ios-controller/VendorLib"])]
        ),
        .testTarget(
            name: "IOSControllerTests",
            path: "ios-controller/Tests/IOSControllerTests",
            dependencies: ["IOSController", "TvremoteCoreFFI", "TvRemoteCoreZig"],
            linkerSettings: [.unsafeFlags(["-L", "ios-controller/VendorLib"])]
        ),
    ]
)
