import Foundation

public let maxProfileNameLength: Int = 24

public enum ProfileNameResult {
    case valid(String)
    case invalid(String)
}

public func validateProfileName(_ raw: String) -> ProfileNameResult {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
        return .invalid("blank")
    }
    if trimmed.count > maxProfileNameLength {
        return .invalid("too_long")
    }
    return .valid(trimmed)
}
