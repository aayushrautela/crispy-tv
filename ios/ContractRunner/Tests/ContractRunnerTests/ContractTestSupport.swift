import Foundation

enum ContractTestError: Error, LocalizedError {
    case invalidFixture(String)

    var errorDescription: String? {
        switch self {
        case .invalidFixture(let message):
            return message
        }
    }
}

func requireString(_ object: [String: Any], _ key: String, fixture: URL) throws -> String {
    guard let value = object[key] as? String else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing string \(key)")
    }
    return value
}

func optionalString(_ object: [String: Any], _ key: String) -> String? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    return value as? String
}

func requireBool(_ object: [String: Any], _ key: String, fixture: URL) throws -> Bool {
    guard let value = object[key] as? Bool else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing bool \(key)")
    }
    return value
}

func requireInt(_ object: [String: Any], _ key: String, fixture: URL) throws -> Int {
    guard let value = intValue(object[key]) else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing int \(key)")
    }
    return value
}

func optionalInt(_ object: [String: Any], _ key: String) -> Int? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    return intValue(value)
}

func requireObject(_ object: [String: Any], _ key: String, fixture: URL) throws -> [String: Any] {
    guard let value = object[key] as? [String: Any] else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing object \(key)")
    }
    return value
}

func optionalObject(_ object: [String: Any], _ key: String) -> [String: Any]? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    return value as? [String: Any]
}

func requireArray(_ object: [String: Any], _ key: String, fixture: URL) throws -> [Any] {
    guard let value = object[key] as? [Any] else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing array \(key)")
    }
    return value
}

func stringArray(_ values: [Any], fixture: URL, key: String) throws -> [String] {
    var output: [String] = []
    for value in values {
        guard let text = value as? String else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): non-string value in \(key)")
        }
        output.append(text)
    }
    return output
}

func intArray(_ values: [Any], fixture: URL, key: String) throws -> [Int] {
    var output: [Int] = []
    for value in values {
        guard let number = intValue(value) else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): non-int value in \(key)")
        }
        output.append(number)
    }
    return output
}

private func intValue(_ value: Any?) -> Int? {
    if let intValue = value as? Int {
        return intValue
    }
    if let number = value as? NSNumber {
        return number.intValue
    }
    return nil
}
