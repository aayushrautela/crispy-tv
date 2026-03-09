import Foundation

public struct CatalogFilter: Equatable {
    public let key: String
    public let value: String

    public init(key: String, value: String) {
        self.key = key
        self.value = value
    }
}

public func buildCatalogUrls(
    baseUrl: String,
    encodedQuery: String? = nil,
    mediaType: String,
    catalogId: String,
    skip: Int = 0,
    limit: Int = 20,
    filters: [CatalogFilter] = []
) -> [String] {
    let normalizedBaseUrl = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).trimmingTrailingSlashes()
    let normalizedMediaType = mediaType.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    let normalizedCatalogId = catalogId.trimmingCharacters(in: .whitespacesAndNewlines)
    let normalizedSkip = max(skip, 0)
    let normalizedLimit = max(limit, 1)
    let normalizedQuery = normalizeEncodedQuery(encodedQuery)
    let normalizedFilters = normalizeCatalogFilters(filters)

    let pathRoot = normalizedBaseUrl + "/catalog/" + encodePathSegment(normalizedMediaType) + "/" + encodePathSegment(normalizedCatalogId)
    var urls: [String] = []

    if normalizedSkip == 0 && normalizedFilters.isEmpty {
        urls.append(pathRoot + ".json" + querySuffix(normalizedQuery))
    }

    var pathExtras: [String] = [
        "skip=\(encodePathSegment(String(normalizedSkip)))",
        "limit=\(encodePathSegment(String(normalizedLimit)))"
    ]
    pathExtras.append(contentsOf: normalizedFilters.map { "\(encodePathSegment($0.key))=\(encodePathSegment($0.value))" })
    urls.append(pathRoot + "/" + pathExtras.joined(separator: "/") + ".json" + querySuffix(normalizedQuery))

    var queryParts: [String] = []
    if let normalizedQuery {
        queryParts.append(normalizedQuery)
    }
    queryParts.append("skip=\(encodeQueryComponent(String(normalizedSkip)))")
    queryParts.append("limit=\(encodeQueryComponent(String(normalizedLimit)))")
    queryParts.append(contentsOf: normalizedFilters.map { "\(encodeQueryComponent($0.key))=\(encodeQueryComponent($0.value))" })
    urls.append(pathRoot + ".json?" + queryParts.joined(separator: "&"))

    return urls
}

private func normalizeCatalogFilters(_ filters: [CatalogFilter]) -> [CatalogFilter] {
    return filters
        .compactMap { filter in
            let key = filter.key.trimmingCharacters(in: .whitespacesAndNewlines)
            let value = filter.value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, !value.isEmpty else {
                return nil
            }
            return CatalogFilter(key: key, value: value)
        }
        .sorted {
            let leftKey = $0.key.lowercased()
            let rightKey = $1.key.lowercased()
            if leftKey != rightKey {
                return leftKey < rightKey
            }
            return $0.value.lowercased() < $1.value.lowercased()
        }
}

private func normalizeEncodedQuery(_ value: String?) -> String? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return nil
    }
    let normalized = trimmed.hasPrefix("?") ? String(trimmed.dropFirst()) : trimmed
    return normalized.isEmpty ? nil : normalized
}

private func querySuffix(_ encodedQuery: String?) -> String {
    guard let encodedQuery else {
        return ""
    }
    return "?\(encodedQuery)"
}

private func encodePathSegment(_ value: String) -> String {
    return encodeQueryComponent(value)
}

private func encodeQueryComponent(_ value: String) -> String {
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}

private extension String {
    func trimmingTrailingSlashes() -> String {
        var result = self
        while result.hasSuffix("/") {
            result.removeLast()
        }
        return result
    }
}
