import SwiftUI

/// Full-page catalog grid ("See all" destination), port of the Android
/// `CatalogScreen`.
struct CatalogListScreen: View {
    let catalogId: String
    let title: String

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel: CatalogListViewModel?

    private let columns = [GridItem(.adaptive(minimum: 240), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 12) {
                if viewModel?.items.isEmpty ?? true {
                    ForEach(0..<6, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: Theme.cardCornerRadius)
                            .fill(Color(.secondarySystemBackground))
                            .aspectRatio(Theme.landscapeAspectRatio, contentMode: .fit)
                            .redacted(reason: .placeholder)
                    }
                } else {
                    ForEach(viewModel?.items ?? []) { item in
                        NavigationLink(value: item.detailsRoute) {
                            LandscapeCardView(
                                title: item.title,
                                backdropUrl: item.backdropUrl ?? item.posterUrl,
                                logoUrl: item.logoUrl,
                                ratingText: item.ratingText,
                                yearText: item.yearText,
                                maturityRating: item.maturityRating,
                                genre: item.genre,
                                width: nil
                            )
                        }
                        .buttonStyle(.plain)
                        .onAppear {
                            if item.id == viewModel?.items.last?.id {
                                Task { await viewModel?.loadNextPage() }
                            }
                        }
                    }
                }

                if let status = viewModel?.statusMessage.nilIfBlank {
                    Text(status)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await ensureViewModel().reload(environment: environment) }
        .task { await ensureViewModel().loadIfNeeded(environment: environment) }
    }

    private func ensureViewModel() -> CatalogListViewModel {
        if let viewModel { return viewModel }
        let created = CatalogListViewModel(catalogId: catalogId)
        viewModel = created
        return created
    }
}
