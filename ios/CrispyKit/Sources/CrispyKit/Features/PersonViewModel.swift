import Foundation
import Observation

/// Port of the Android `PersonDetailsViewModel`: person detail + known-for.
@MainActor
@Observable
public final class PersonViewModel {
public     let personId: String
public     let initialProfileUrl: String?

    public private(set) var detail: PersonDetail?
    public private(set) var isLoading = false
    public private(set) var errorMessage = ""

public     init(personId: String, initialProfileUrl: String?) {
        self.personId = personId
        self.initialProfileUrl = initialProfileUrl
    }

public     func loadIfNeeded(environment: AppEnvironment) async {
        guard detail == nil else { return }
        await load(environment: environment)
    }

public     func load(environment: AppEnvironment) async {
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
