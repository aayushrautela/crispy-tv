import CrispyKit
import SwiftUI

/// Person page, port of the Android `PersonDetailsScreen`: avatar, bio,
/// known-for grid.
struct PersonScreen: View {
    let personId: String
    let initialProfileUrl: String?

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel: PersonViewModel?

    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 12)]

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .task { await ensureViewModel().loadIfNeeded(environment: environment) }
    }

    @ViewBuilder
    private func content(_ viewModel: PersonViewModel) -> some View {
        if viewModel.isLoading && viewModel.detail == nil {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let detail = viewModel.detail {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 16) {
                        ZStack {
                            Circle().fill(Color(.tertiarySystemFill))
                            if let url = (detail.profileUrl ?? initialProfileUrl).nilIfBlank {
                                RemoteImage(url: url)
                                    .clipShape(.circle)
                            } else {
                                Image(systemName: "person.fill")
                                    .font(.title2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .frame(width: 88, height: 88)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(detail.name)
                                .font(.title2.weight(.bold))
                            if let department = detail.knownForDepartment.nilIfBlank {
                                Text(department)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            if let birthday = detail.birthday.nilIfBlank {
                                Text("Born \(birthday)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)

                    if let place = detail.placeOfBirth.nilIfBlank {
                        Text(place)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 16)
                    }

                    if let biography = detail.biography.nilIfBlank {
                        Text(biography)
                            .font(.body)
                            .foregroundStyle(.primary.opacity(0.9))
                            .padding(.horizontal, 16)
                    }

                    ForEach(detail.knownForRails, id: \.rail.title) { rail in
                        VStack(alignment: .leading, spacing: 12) {
                            Text(rail.rail.title)
                                .font(.headline)
                                .padding(.horizontal, 16)

                            LazyVGrid(columns: columns, spacing: 12) {
                                ForEach(rail.items) { item in
                                    NavigationLink(value: AppRoute.details(
                                        itemId: item.itemId,
                                        itemType: item.mediaType
                                    )) {
                                        LandscapeCardView(
                                            title: item.title,
                                            artworkUrl: item.artworkUrl,
                                            logoUrl: item.logoUrl,
                                            ratingText: formatRating(item.rating),
                                            yearText: item.releaseYear.map(String.init),
                                            maturityRating: nil,
                                            genre: nil,
                                            width: nil
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 16)
                        }
                    }

                    if !viewModel.errorMessage.isEmpty {
                        Text(viewModel.errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 16)
                    }
                }
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .refreshable { await viewModel.load(environment: environment) }
        } else {
            Text(viewModel.errorMessage.nilIfBlank ?? "Could not load this person.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func ensureViewModel() -> PersonViewModel {
        if let viewModel { return viewModel }
        let created = PersonViewModel(personId: personId, initialProfileUrl: initialProfileUrl)
        viewModel = created
        return created
    }
}
