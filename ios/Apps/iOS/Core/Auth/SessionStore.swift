import Foundation
import Security

/// The single representation of a Supabase auth session, mirroring
/// `com.crispy.tv.accounts.Session`.
struct Session: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let expiresAtEpochSec: Int64?
    let userId: String?
    let email: String?
    let anonymous: Bool
}

protocol SessionStoring {
    func current() -> Session?
    func save(_ session: Session)
    func clear()
}

/// Keychain-backed session persistence (Android uses EncryptedSharedPreferences).
final class KeychainSessionStore: SessionStoring {
    private let service: String
    private let account = "default"

    init(service: String = "com.crispy.rewrite.session") {
        self.service = service
    }

    func current() -> Session? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }

    func save(_ session: Session) {
        clear()
        guard let data = try? JSONEncoder().encode(session) else { return }
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

    func clear() {
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
final class ActiveProfileStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private func key(_ userId: String?) -> String {
        "crispy.activeProfile.\(userId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "")"
    }

    func activeProfileId(userId: String?) -> String? {
        defaults.string(forKey: key(userId))?.nilIfBlank
    }

    func setActiveProfileId(_ profileId: String?, userId: String?) {
        let storageKey = key(userId)
        if let profileId = profileId?.nilIfBlank {
            defaults.set(profileId, forKey: storageKey)
        } else {
            defaults.removeObject(forKey: storageKey)
        }
    }

    func clear(userId: String?) {
        defaults.removeObject(forKey: key(userId))
    }
}
