import Foundation

struct CrispyHttpResponse {
    let code: Int
    let body: String
}

final class CrispyHttpClient {
    private let session: URLSession

    init(session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 45
        configuration.timeoutIntervalForResource = 120
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }()) {
        self.session = session
    }

    func get(url: URL, headers: [String: String], timeout: TimeInterval? = nil) async throws -> CrispyHttpResponse {
        try await execute(method: "GET", url: url, headers: headers, body: nil, timeout: timeout)
    }

    func postJson(url: URL, jsonBody: String, headers: [String: String], timeout: TimeInterval? = nil) async throws -> CrispyHttpResponse {
        try await execute(method: "POST", url: url, headers: headers, body: jsonBody, timeout: timeout)
    }

    func putJson(url: URL, jsonBody: String, headers: [String: String], timeout: TimeInterval? = nil) async throws -> CrispyHttpResponse {
        try await execute(method: "PUT", url: url, headers: headers, body: jsonBody, timeout: timeout)
    }

    func delete(url: URL, headers: [String: String], timeout: TimeInterval? = nil) async throws -> CrispyHttpResponse {
        try await execute(method: "DELETE", url: url, headers: headers, body: nil, timeout: timeout)
    }

    private func execute(
        method: String,
        url: URL,
        headers: [String: String],
        body: String?,
        timeout: TimeInterval?
    ) async throws -> CrispyHttpResponse {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.allHTTPHeaderFields = headers
        if let body {
            request.httpBody = Data(body.utf8)
        }
        if let timeout {
            request.timeoutInterval = timeout
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw CrispyHttpError.invalidResponse
        }
        return CrispyHttpResponse(code: http.statusCode, body: String(data: data, encoding: .utf8) ?? "")
    }
}

enum CrispyHttpError: Error {
    case invalidResponse
}
