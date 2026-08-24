import Foundation

/// URLSession port of the Android `SupabaseAccountClient` (email sign-in/up,
/// token refresh, sign-out). Envelope handling matches the Kotlin client.
public final class SupabaseAccountClient {
    public struct SignUpResult {
        public let session: Session?
        public let message: String
    }

    private let httpClient: CrispyHttpClient
    private let baseURL: String
    private let publishableKey: String
    private let tokenStore: SessionStoring
    private let nowEpochSeconds: () -> Int64

    private var refreshInFlight: Task<Session?, Never>?

public init(
        httpClient: CrispyHttpClient,
        supabaseURL: String,
        publishableKey: String,
        tokenStore: SessionStoring,
        nowEpochSeconds: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }
    ) {
        self.httpClient = httpClient
        self.baseURL = supabaseURL.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.publishableKey = publishableKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.tokenStore = tokenStore
        self.nowEpochSeconds = nowEpochSeconds
    }

public func isConfigured() -> Bool {
        !baseURL.isEmpty && !publishableKey.isEmpty
    }

public func currentSession() -> Session? {
        tokenStore.current()
    }

public func ensureValidSession() async -> Session? {
        guard let existing = tokenStore.current() else { return nil }
        if !shouldRefresh(existing) { return existing }
        if !isConfigured() {
            saveSession(nil)
            return nil
        }

        if let inFlight = refreshInFlight {
            return await inFlight.value
        }
        let task = Task<Session?, Never> { [weak self] in
            guard let self else { return nil }
            return await self.refreshLocked()
        }
        refreshInFlight = task
        defer { refreshInFlight = nil }
        return await task.value
    }

public func signInWithEmail(email: String, password: String) async throws -> Session {
        try checkConfigured()
        let payload: [String: Any] = [
            "email": email.trimmingCharacters(in: .whitespacesAndNewlines),
            "password": password,
        ]
        let body = try await postAuth(path: "/auth/v1/token?grant_type=password", payload: payload)
        guard let session = parseSession(try JsonParser.parseObject(body)) else {
            throw AuthError.signinFailed("Sign-in did not return a session.")
        }
        saveSession(session)
        return session
    }

public func signUpWithEmail(email: String, password: String) async throws -> SignUpResult {
        try checkConfigured()
        let payload: [String: Any] = [
            "email": email.trimmingCharacters(in: .whitespacesAndNewlines),
            "password": password,
        ]
        let body = try await postAuth(path: "/auth/v1/signup", payload: payload)
        let json = try JsonParser.parseObject(body)
        if let session = parseSession(json) {
            saveSession(session)
            return SignUpResult(session: session, message: "Account created and signed in.")
        }
        let hasUser = json.jsonObject("user")?.jsonString("id") != nil
        return SignUpResult(
            session: nil,
            message: hasUser ? "Account created. Confirm your email, then sign in." : "Account created."
        )
    }

    /// Revokes the server-side session. Local wipe stays with the caller.
public func signOut() async {
        guard isConfigured(), let session = tokenStore.current() else { return }
        guard !session.accessToken.hasPrefix("cp_pat_") else { return }
        _ = try? await postAuth(
            path: "/auth/v1/logout",
            payload: ["scope": "global"],
            bearerToken: session.accessToken
        )
    }

    // MARK: - Refresh

    private func refreshLocked() async -> Session? {
        guard let latest = tokenStore.current() else { return nil }
        if !shouldRefresh(latest) { return latest }
        guard !latest.refreshToken.isEmpty else {
            saveSession(nil)
            return nil
        }

        do {
            let body = try await postAuth(
                path: "/auth/v1/token?grant_type=refresh_token",
                payload: ["refresh_token": latest.refreshToken]
            )
            guard let session = parseSession(try JsonParser.parseObject(body)) else {
                return latest
            }
            saveSession(session)
            return session
        } catch let error as AuthError {
            if case .invalidRefreshToken = error {
                saveSession(nil)
                return nil
            }
            return latest
        } catch {
            return latest
        }
    }

    // MARK: - Plumbing

    private func parseSession(_ json: [String: Any]) -> Session? {
        guard let accessToken = json.jsonString("access_token") else { return nil }
        var expiresAt = json.jsonInt("expires_at").map(Int64.init).flatMap { $0 > 0 ? $0 : nil }
        if expiresAt == nil, let expiresIn = json.jsonInt("expires_in").map(Int64.init), expiresIn > 0 {
            expiresAt = nowEpochSeconds() + expiresIn
        }
        let user = json.jsonObject("user")
        return Session(
            accessToken: accessToken,
            refreshToken: json.jsonString("refresh_token") ?? "",
            expiresAtEpochSec: expiresAt,
            userId: user?.jsonString("id"),
            email: user?.jsonString("email"),
            anonymous: user?.jsonBool("is_anonymous", defaultValue: false) ?? false
        )
    }

    private func shouldRefresh(_ session: Session) -> Bool {
        guard let expiresAt = session.expiresAtEpochSec else { return false }
        return expiresAt <= nowEpochSeconds() + Self.expirySkewSeconds
    }

    private func baseHeaders() -> [String: String] {
        [
            "apikey": publishableKey,
            "Content-Type": "application/json",
            "Accept": "application/json",
        ]
    }

    private func authHeaders(_ token: String) -> [String: String] {
        var headers = baseHeaders()
        headers["Authorization"] = "Bearer \(token.trimmingCharacters(in: .whitespacesAndNewlines))"
        return headers
    }

    private func requireConfiguredBaseURL() throws -> String {
        try checkConfigured()
        return baseURL
    }

    private func checkConfigured() throws {
        if !isConfigured() {
            throw AuthError.notConfigured
        }
    }

    private func postAuth(path: String, payload: [String: Any], bearerToken: String? = nil) async throws -> String {
        let url = URL(string: try requireConfiguredBaseURL() + path)
        let headers = bearerToken.map(authHeaders) ?? baseHeaders()
        let response = try await httpClient.postJson(
            url: try requireValid(url),
            jsonBody: try JsonParser.encodeObject(payload),
            headers: headers,
            timeout: Self.callTimeoutSeconds
        )
        guard (200...299).contains(response.code) else {
            throw authError(code: response.code, body: response.body)
        }
        return response.body
    }

    private func requireValid(_ url: URL?) throws -> URL {
        guard let url else { throw AuthError.notConfigured }
        return url
    }

    private func authError(code: Int, body: String) -> AuthError {
        let message = extractErrorMessage(body)
        if (400...401).contains(code), let lowered = message?.lowercased() {
            let invalidRefresh =
                lowered.contains("invalid refresh token") ||
                lowered.contains("invalid grant") ||
                (lowered.contains("refresh token") && lowered.contains("invalid")) ||
                (lowered.contains("refresh token") && lowered.contains("expired")) ||
                lowered.contains("session_not_found")
            if invalidRefresh {
                return .invalidRefreshToken
            }
        }
        return .http(code: code, message: message ?? "HTTP \(code)")
    }

    private func extractErrorMessage(_ rawBody: String) -> String? {
        let trimmed = rawBody.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let parsed = try? JsonParser.parseObject(trimmed) else { return trimmed }
        for key in ["message", "msg", "error_description", "error"] {
            if let value = parsed.jsonString(key) { return value }
        }
        return trimmed
    }

    private func saveSession(_ session: Session?) {
        if let session {
            tokenStore.save(session)
        } else {
            tokenStore.clear()
        }
    }

    public enum AuthError: Error {
        case notConfigured
        case signinFailed(String)
        case invalidRefreshToken
        case http(code: Int, message: String)

        public var localizedMessage: String {
            switch self {
            case .notConfigured:
                return "Supabase is not configured."
            case .signinFailed(let message):
                return message
            case .invalidRefreshToken:
                return "Session expired. Please sign in again."
            case .http(_, let message):
                return message
            }
        }
    }

    private static let callTimeoutSeconds: TimeInterval = 10
    private static let expirySkewSeconds: Int64 = 30
}
