import Foundation

/// Lenient JSON accessors mirroring the Kotlin `org.json` opt* helpers used by
/// the Android backend parsers. Missing keys and wrong types yield nil/defaults.
extension Dictionary where Key == String, Value == Any {
public     func jsonString(_ key: String) -> String? {
        (self[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
    }

public     func jsonInt(_ key: String) -> Int? {
        let value = self[key]
        if let number = value as? NSNumber { return number.intValue }
        if let string = value as? String { return Int(string.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return nil
    }

public     func jsonDouble(_ key: String) -> Double? {
        let value = self[key]
        if let number = value as? NSNumber { return number.doubleValue }
        if let string = value as? String { return Double(string.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return nil
    }

public     func jsonBool(_ key: String, defaultValue: Bool) -> Bool {
        let value = self[key]
        if let number = value as? NSNumber { return number.boolValue }
        if let string = value as? String {
            switch string.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "1": return true
            case "false", "0": return false
            default: return defaultValue
            }
        }
        return defaultValue
    }

public     func jsonObject(_ key: String) -> [String: Any]? {
        self[key] as? [String: Any]
    }

public     func jsonArray(_ key: String) -> [[String: Any]] {
        guard let array = self[key] as? [Any] else { return [] }
        return array.compactMap { $0 as? [String: Any] }
    }

public     func jsonStringList(_ key: String) -> [String] {
        guard let array = self[key] as? [Any] else { return [] }
        return array.compactMap { ($0 as? String)?.nilIfBlank }
    }

public     func jsonStringMap(_ key: String) -> [String: String] {
        guard let object = self[key] as? [String: Any] else { return [:] }
        var result: [String: String] = [:]
        for (mapKey, mapValue) in object {
            if let stringValue = mapValue as? String, let trimmed = stringValue.nilIfBlank {
                result[mapKey] = trimmed
            } else if let number = mapValue as? NSNumber {
                result[mapKey] = number.stringValue
            }
        }
        return result
    }
}

public enum JsonParser {
public     static func parseObject(_ body: String) throws -> [String: Any] {
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CrispyParseError.emptyBody
        }
        guard let object = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any] else {
            throw CrispyParseError.notAnObject
        }
        return object
    }

public     static func encodeObject(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object)
        return String(data: data, encoding: .utf8) ?? "{}"
    }
}

public enum CrispyParseError: Error {
    case emptyBody
    case notAnObject
}
