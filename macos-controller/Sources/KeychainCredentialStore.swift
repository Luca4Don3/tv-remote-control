import Foundation
import Security

struct StoredCredential: Codable, Sendable {
    let tvId: String
    let controllerId: String
    let secret: Data
    let certificateFingerprint: Data
    let displayName: String
    let lastEndpoint: String

    init(tvId: String, controllerId: String, secret: Data, certificateFingerprint: Data,
         displayName: String, lastEndpoint: String) throws {
        guard !tvId.isEmpty, !controllerId.isEmpty else { throw KeychainStoreError.invalidRecord }
        guard secret.count == 32, certificateFingerprint.count == 32 else { throw KeychainStoreError.invalidRecord }
        self.tvId = tvId
        self.controllerId = controllerId
        self.secret = secret
        self.certificateFingerprint = certificateFingerprint
        self.displayName = displayName
        self.lastEndpoint = lastEndpoint
    }
}

enum KeychainStoreError: Error {
    case invalidRecord
    case unexpectedData
    case status(OSStatus)
}

final class KeychainCredentialStore: @unchecked Sendable {
    static let service = "dev.lucasdone.tv-remote-control"
    private let lock = NSLock()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    func put(_ record: StoredCredential) throws {
        let data = try encoder.encode(record)
        try lock.withLock {
            let query = baseQuery(account: record.tvId)
            // kSecAttrAccessible 仅在创建（SecItemAdd）时生效；更新路径必须移除，否则可能返回参数错误
            let attributes: [CFString: Any] = [
                kSecValueData: data,
                kSecAttrLabel: record.displayName,
            ]
            let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if updateStatus == errSecItemNotFound {
                var insertion = query
                attributes.forEach { insertion[$0] = $1 }
                insertion[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                let addStatus = SecItemAdd(insertion as CFDictionary, nil)
                guard addStatus == errSecSuccess else { throw KeychainStoreError.status(addStatus) }
            } else if updateStatus != errSecSuccess {
                throw KeychainStoreError.status(updateStatus)
            }
        }
    }

    func get(tvId: String) throws -> StoredCredential? {
        try lock.withLock {
            var query = baseQuery(account: tvId)
            query[kSecReturnData] = true
            query[kSecMatchLimit] = kSecMatchLimitOne
            var item: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &item)
            if status == errSecItemNotFound { return nil }
            guard status == errSecSuccess else { throw KeychainStoreError.status(status) }
            guard let data = item as? Data else { throw KeychainStoreError.unexpectedData }
            let record = try decoder.decode(StoredCredential.self, from: data)
            guard record.tvId == tvId, record.secret.count == 32, record.certificateFingerprint.count == 32 else {
                throw KeychainStoreError.invalidRecord
            }
            return record
        }
    }

    func remove(tvId: String) throws {
        try lock.withLock {
            let status = SecItemDelete(baseQuery(account: tvId) as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw KeychainStoreError.status(status)
            }
        }
    }

    func putBlob(credentialID: Data, value: Data) throws {
        guard !credentialID.isEmpty, credentialID.count <= 64,
              !value.isEmpty, value.count <= 4096 else {
            throw KeychainStoreError.invalidRecord
        }
        let account = credentialID.map { String(format: "%02x", $0) }.joined()
        try lock.withLock {
            let query = baseQuery(account: account)
            // kSecAttrAccessible 仅在创建时生效，更新路径从 attributes 中移除
            let attributes: [CFString: Any] = [
                kSecValueData: value,
                kSecAttrLabel: "TV Remote Control paired credential",
            ]
            let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if updateStatus == errSecItemNotFound {
                var insertion = query
                attributes.forEach { insertion[$0] = $1 }
                insertion[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                let addStatus = SecItemAdd(insertion as CFDictionary, nil)
                guard addStatus == errSecSuccess else { throw KeychainStoreError.status(addStatus) }
            } else if updateStatus != errSecSuccess {
                throw KeychainStoreError.status(updateStatus)
            }
        }
    }

    func getBlob(credentialID: Data) throws -> Data? {
        guard !credentialID.isEmpty, credentialID.count <= 64 else {
            throw KeychainStoreError.invalidRecord
        }
        let account = credentialID.map { String(format: "%02x", $0) }.joined()
        return try lock.withLock {
            var query = baseQuery(account: account)
            query[kSecReturnData] = true
            query[kSecMatchLimit] = kSecMatchLimitOne
            var item: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &item)
            if status == errSecItemNotFound { return nil }
            guard status == errSecSuccess else { throw KeychainStoreError.status(status) }
            guard let data = item as? Data, !data.isEmpty, data.count <= 4096 else {
                throw KeychainStoreError.unexpectedData
            }
            return data
        }
    }

    func removeBlob(credentialID: Data) throws {
        guard !credentialID.isEmpty, credentialID.count <= 64 else {
            throw KeychainStoreError.invalidRecord
        }
        let account = credentialID.map { String(format: "%02x", $0) }.joined()
        try remove(tvId: account)
    }

    private func baseQuery(account: String) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: Self.service,
            kSecAttrAccount: account,
            kSecAttrSynchronizable: false,
        ]
    }
}

private enum CoreCredentialResult {
    static let ok: Int32 = 0
    static let invalidArgument: Int32 = 1
    static let bufferTooSmall: Int32 = 4
    static let ioError: Int32 = 5
    static let notFound: Int32 = 6
}

func configureMacOSCredentialCallbacks(
    _ configuration: inout tvrc_config,
    store: KeychainCredentialStore
) {
    // C 库在 tvrc_destroy 之前都可能调用凭据回调；passRetained 保证 store 存活到
    // CoreHandle.deinit 中 tvrc_destroy 之后显式 release，避免回调 use-after-free
    configuration.credential_context = Unmanaged.passRetained(store).toOpaque()
    configuration.credentials_put = tvrcMacOSCredentialsPut
    configuration.credentials_get = tvrcMacOSCredentialsGet
    configuration.credentials_remove = tvrcMacOSCredentialsRemove
}

@_cdecl("tvrc_macos_credentials_put")
func tvrcMacOSCredentialsPut(
    _ context: UnsafeMutableRawPointer?,
    _ credentialID: UnsafePointer<UInt8>?,
    _ credentialIDLength: UInt32,
    _ secret: UnsafePointer<UInt8>?,
    _ secretLength: UInt32
) -> Int32 {
    guard let context, let credentialID, let secret,
          credentialIDLength > 0, credentialIDLength <= 64,
          secretLength > 0, secretLength <= 4096 else {
        return CoreCredentialResult.invalidArgument
    }
    let store = Unmanaged<KeychainCredentialStore>.fromOpaque(context).takeUnretainedValue()
    do {
        try store.putBlob(
            credentialID: Data(bytes: credentialID, count: Int(credentialIDLength)),
            value: Data(bytes: secret, count: Int(secretLength))
        )
        return CoreCredentialResult.ok
    } catch {
        return CoreCredentialResult.ioError
    }
}

@_cdecl("tvrc_macos_credentials_get")
func tvrcMacOSCredentialsGet(
    _ context: UnsafeMutableRawPointer?,
    _ credentialID: UnsafePointer<UInt8>?,
    _ credentialIDLength: UInt32,
    _ secret: UnsafeMutablePointer<UInt8>?,
    _ secretCapacity: UInt32,
    _ secretLength: UnsafeMutablePointer<UInt32>?
) -> Int32 {
    guard let context, let credentialID, let secretLength,
          credentialIDLength > 0, credentialIDLength <= 64 else {
        return CoreCredentialResult.invalidArgument
    }
    let store = Unmanaged<KeychainCredentialStore>.fromOpaque(context).takeUnretainedValue()
    do {
        guard let value = try store.getBlob(
            credentialID: Data(bytes: credentialID, count: Int(credentialIDLength))
        ) else {
            secretLength.pointee = 0
            return CoreCredentialResult.notFound
        }
        secretLength.pointee = UInt32(value.count)
        guard value.count <= secretCapacity else { return CoreCredentialResult.bufferTooSmall }
        guard value.isEmpty || secret != nil else { return CoreCredentialResult.invalidArgument }
        if let secret {
            value.copyBytes(to: secret, count: value.count)
        }
        return CoreCredentialResult.ok
    } catch {
        secretLength.pointee = 0
        return CoreCredentialResult.ioError
    }
}

@_cdecl("tvrc_macos_credentials_remove")
func tvrcMacOSCredentialsRemove(
    _ context: UnsafeMutableRawPointer?,
    _ credentialID: UnsafePointer<UInt8>?,
    _ credentialIDLength: UInt32
) -> Int32 {
    guard let context, let credentialID,
          credentialIDLength > 0, credentialIDLength <= 64 else {
        return CoreCredentialResult.invalidArgument
    }
    let store = Unmanaged<KeychainCredentialStore>.fromOpaque(context).takeUnretainedValue()
    do {
        try store.removeBlob(credentialID: Data(bytes: credentialID, count: Int(credentialIDLength)))
        return CoreCredentialResult.ok
    } catch {
        return CoreCredentialResult.ioError
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
