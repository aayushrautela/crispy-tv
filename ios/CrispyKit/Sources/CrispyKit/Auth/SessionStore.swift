import Foundation
import Security

/// The single representation of a Supabase auth session, mirroring
/// `com.crispy.tv.accounts.Session`.
public struct Session: Codable, Equatable {
public let accessToken: String
public let refreshToken: String
public let expiresAtEpochSec: Int64?
public let userId: String?
public let email: String?
public let anonymous: Bool
}

public protocol SessionStoring {
    func current() -> Session?
    func save(_ session: Session)
    func clear()
}

/// Keychain-backed session persistence (Android uses EncryptedSharedPreferences).
public final class KeychainSessionStore: SessionStoring {
    private let service: String
    private let account = "default"

public init(service: String = "com.crispy.rewrite.session") {
        self.service = service
    }

public func current() -> Session? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }

public func save(_ session: Session) {
        clear()
        guard let data = try? JSONEncoder().encode(session) else { return }
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

public func clear() {
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

/// Persists the active profile per user (Android: SharedPreferences ActiveProfileStore).
public final class ActiveProfileStore {
    private let defaults: UserDefaults

public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private func key(_ userId: String?) -> String {
        "crispy.activeProfile.\(userId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "")"
    }

public func activeProfileId(userId: String?) -> String? {
        defaults.string(forKey: key(userId))?.nilIfBlank
    }

public func setActiveProfileId(_ profileId: String?, userId: String?) {
        let storageKey = key(userId)
        if let profileId = profileId?.nilIfBlank {
            defaults.set(profileId, forKey: storageKey)
        } else {
            defaults.removeObject(forKey: storageKey)
        }
    }

public func clear(userId: String?) {
        defaults.removeObject(forKey: key(userId))
    }
}
