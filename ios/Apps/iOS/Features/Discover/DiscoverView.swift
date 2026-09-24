import CrispyKit
import SwiftUI

/// Discover page mirroring the web app + Android `DiscoverScreen`: type,
/// genre and sort filters on top, then an adaptive grid of landscape cards
/// with infinite paging through `/v1/browse/titles`.
struct DiscoverScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = DiscoverViewModel()

    private let columns = [GridItem(.adaptive(minimum: 240), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 12) {
                gridHeader

                if viewModel.isLoadingFirstPage && viewModel.items.isEmpty {
                    ForEach(0..<6, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: Theme.cardCornerRadius)
                            .fill(Color(.secondarySystemBackground))
                            .aspectRatio(Theme.landscapeAspectRatio, contentMode: .fit)
                            .redacted(reason: .placeholder)
                    }
                } else if viewModel.items.isEmpty {
                    emptyState
                } else {
                    ForEach(viewModel.items) { item in
                        NavigationLink(value: item.detailsRoute) {
                            LandscapeCardView(
                                title: item.title,
                                artworkUrl: item.artworkUrl,
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
                            if item.id == viewModel.items.last?.id {
                                Task { await viewModel.loadNextPage(environment: environment) }
                            }
                        }
                    }
                }

                if !viewModel.items.isEmpty && !viewModel.statusMessage.isEmpty {
                    Text(viewModel.statusMessage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 8)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .navigationTitle("Discover")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ProfileMenuButton(profile: nil) {}
            }
        }
        .refreshable { await viewModel.reload(environment: environment) }
        .task { await viewModel.loadIfNeeded(environment: environment) }
    }

    @ViewBuilder
    private var gridHeader: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Picker("Type", selection: Binding(
                    get: { viewModel.typeFilter },
                    set: { filter in
                        Task { await viewModel.setTypeFilter(filter, environment: environment) }
                    }
                )) {
                    ForEach(DiscoverViewModel.TypeFilter.allCases) { filter in
                        Text(filter.label).tag(filter)
                    }
                }
                .pickerStyle(.segmented)

                Menu {
                    Button("All genres") {
                        Task { await viewModel.setGenre(nil, environment: environment) }
                    }
                    ForEach(DiscoverViewModel.genres) { genre in
                        Button {
                            Task { await viewModel.setGenre(genre, environment: environment) }
                        } label: {
                            Text(genre.label)
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(viewModel.genre?.label ?? "All genres")
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.caption2)
                    }
                    .crispyChip(isSelected: viewModel.genre != nil)
                }
                .fixedSize()

                Menu {
                    ForEach(DiscoverViewModel.SortFilter.allCases) { filter in
                        Button {
                            Task { await viewModel.setSortFilter(filter, environment: environment) }
                        } label: {
                            Text(filter.label)
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(viewModel.sortFilter.label)
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.caption2)
                    }
                    .crispyChip()
                }
                .fixedSize()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var emptyState: some View {
        Text(emptyMessage)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 32)
    }

    private var emptyMessage: String {
        if !viewModel.statusMessage.isEmpty {
            return viewModel.statusMessage
        }
        return "No results found. Try changing the filters."
    }
}