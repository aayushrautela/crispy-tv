import Foundation
import Observation

/// Port of the Android `PersonDetailsViewModel`: person detail + known-for.
@MainActor
@Observable
final class PersonViewModel {
    let personId: String
    let initialProfileUrl: String?

    private(set) var detail: PersonDetail?
    private(set) var isLoading = false
    private(set) var errorMessage = ""

    init(personId: String, initialProfileUrl: String?) {
        self.personId = personId
        self.initialProfileUrl = initialProfileUrl
    }

    func loadIfNeeded(environment: AppEnvironment) async {
        guard detail == nil else { return }
        await load(environment: environment)
    }

    func load(environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else {
            errorMessage = "Sign in to view this person."
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            detail = try await environment.backend.getMetadataPersonDetail(
                accessToken: context.accessToken,
                personId: personId
            )
            errorMessage = ""
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
