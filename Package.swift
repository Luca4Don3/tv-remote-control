// swift-tools-version:5.9
// iOS XCTest 运行级门禁的 SwiftPM 清单（仅 CI 与 macOS 开发者使用；产品构建仍以 CI 链接门禁为准）。
// 构建期前提（见 ci.yml「iOS XCTest run gate」步骤）：
//   - ios-controller/VendorLib/：Rust aarch64-apple-ios-sim 与 Zig aarch64-ios-simulator
//     静态库（libtvremote_core.a / libtv_remote_core.a / libmbed*.a）
//   - ios-controller/VendorModules/：从 windows-controller/include 拷贝的头文件（module.modulemap 已入库）
//   - ios-controller/Sources/RustBindings/：从 core-rs/bindings/swift 拷贝的 tvremote_core.swift
import PackageDescription

let vendorLibDir = "ios-controller/VendorLib"

let package = Package(
    name: "TVRemoteControllerIOS",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "IOSController", targets: ["IOSController"]),
    ],
    targets: [
        // Rust 核心 FFI 头（CI 从 core-rs/bindings/swift 拷贝为标准名 module.modulemap；
        // 实现静态库由 IOSController 的 linkerSettings 提供）
        .systemLibrary(
            name: "TvremoteCoreFFI",
            path: "ios-controller/VendorModules/Rust"
        ),
        // Zig 协议核心 C 头（CI 拷入 VendorModules；实现静态库由 IOSController 的 linkerSettings 提供）
        .systemLibrary(
            name: "TvRemoteCoreZig",
            path: "ios-controller/VendorModules"
        ),
        .target(
            name: "IOSController",
            dependencies: ["TvremoteCoreFFI", "TvRemoteCoreZig"],
            path: "ios-controller/Sources",
            sources: [
                "ControllerApp.swift",
                "KeychainCredentialStore.swift",
                "WsDebugClient.swift",
                "RustBindings/tvremote_core.swift",
            ],
            linkerSettings: [
                .linkedLibrary("tvremote_core"),
                .linkedLibrary("tv_remote_core"),
                .linkedLibrary("mbedtls"),
                .linkedLibrary("mbedx509"),
                .linkedLibrary("mbedcrypto"),
                .unsafeFlags(["-L", "core-rs/target/aarch64-apple-ios-sim/release"]),
                .unsafeFlags(["-L", vendorLibDir]),
            ]
        ),
        .testTarget(
            name: "IOSControllerTests",
            dependencies: ["IOSController", "TvremoteCoreFFI", "TvRemoteCoreZig"],
            path: "ios-controller/Tests/IOSControllerTests",
            linkerSettings: [
                .linkedLibrary("tvremote_core"),
                .linkedLibrary("tv_remote_core"),
                .linkedLibrary("mbedtls"),
                .linkedLibrary("mbedx509"),
                .linkedLibrary("mbedcrypto"),
                .unsafeFlags(["-L", "core-rs/target/aarch64-apple-ios-sim/release"]),
                .unsafeFlags(["-L", vendorLibDir]),
            ]
        ),
    ]
)
