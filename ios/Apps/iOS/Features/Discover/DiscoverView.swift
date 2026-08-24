import CrispyKit
import SwiftUI

/// Discover page mirroring the Android `DiscoverScreen`: filter + catalog
/// chips, then an adaptive grid of landscape cards with paging.
struct DiscoverScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = DiscoverViewModel()
    @State private var showCatalogPicker = false

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
                            if item.id == viewModel.items.last?.id {
                                Task { await viewModel.loadNextPage(environment: environment) }
                            }
                        }
                    }
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
        .sheet(isPresented: $showCatalogPicker) {
            catalogPicker
        }
    }

    @ViewBuilder
    private var gridHeader: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Button {
                    // Type filter cycles All → Movies → Series like the Android sheet.
                    let all = DiscoverViewModel.TypeFilter.allCases
                    let next = all[(all.firstIndex(of: viewModel.typeFilter).map { $0 + 1 } ?? 0) % all.count]
                    Task { await viewModel.setTypeFilter(next, environment: environment) }
                } label: {
                    HStack(spacing: 4) {
                        Text(viewModel.typeFilter.rawValue)
                        Image(systemName: "chevron.down")
                            .font(.caption2)
                    }
                }
                .buttonStyle(.glass)

                Button {
                    showCatalogPicker = true
                } label: {
                    HStack(spacing: 4) {
                        Text(viewModel.selectedCatalog?.title ?? "Select catalog")
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.caption2)
                    }
                }
                .buttonStyle(.glass)
            }

            if let selected = viewModel.selectedCatalog {
                Text("\(selected.title) | \(viewModel.typeFilter.rawValue)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if !viewModel.statusMessage.isEmpty {
                Text(viewModel.statusMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
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
        viewModel.selectedCatalog == nil ? "Select a catalog to start discovering" : "No content found"
    }

    private var catalogPicker: some View {
        NavigationStack {
            List(viewModel.catalogs) { option in
                Button {
                    Task {
                        await viewModel.select(option, environment: environment)
                        showCatalogPicker = false
                    }
                } label: {
                    HStack {
                        Text(option.title)
                        Spacer()
                        if option.id == viewModel.selectedCatalog?.id {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Theme.accent)
                        }
                    }
                }
                .foregroundStyle(.primary)
            }
            .navigationTitle("Catalogs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { showCatalogPicker = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
