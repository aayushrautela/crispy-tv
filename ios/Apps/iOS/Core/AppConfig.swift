import Foundation

struct AppConfig {
    let backendURL: String
    let supabaseURL: String
    let supabasePublishableKey: String

    static func load(bundle: Bundle = .main, arguments: [String] = ProcessInfo.processInfo.arguments) -> AppConfig {
        func launchArg(_ name: String) -> String? {
            guard let index = arguments.firstIndex(of: name), index + 1 < arguments.count else { return nil }
            return arguments[index + 1].trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        }

        func plistString(_ key: String) -> String {
            (bundle.object(forInfoDictionaryKey: key) as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        return AppConfig(
            backendURL: launchArg("-crispyBackendURL") ?? plistString("CRISPY_BACKEND_URL"),
            supabaseURL: launchArg("-supabaseURL") ?? plistString("SUPABASE_URL"),
            supabasePublishableKey: launchArg("-supabaseKey") ?? plistString("SUPABASE_PUBLISHABLE_KEY")
        )
    }
}

extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
