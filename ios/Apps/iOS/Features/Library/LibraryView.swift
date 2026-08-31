import CrispyKit
import SwiftUI

/// Library page mirroring the Android `LibraryScreen`: section chips
/// (History / Watchlist / Ratings) over an adaptive grid with paging.
struct LibraryScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = LibraryViewModel()

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
                    Text("Nothing here yet")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 32)
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
                                progressPercent: item.progressPercent,
                                width: nil
                            )
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button {
                                Task { await viewModel.setWatched(item, watched: true, environment: environment) }
                            } label: {
                                Label("Mark watched", systemImage: "checkmark.circle")
                            }
                            Button(role: .destructive) {
                                Task { await viewModel.setWatched(item, watched: false, environment: environment) }
                            } label: {
                                Label("Mark unwatched", systemImage: "xmark.circle")
                            }
                            Button {
                                Task { await viewModel.toggleWatchlist(item, environment: environment) }
                            } label: {
                                Label(
                                    item.watchlisted ? "Remove from watchlist" : "Add to watchlist",
                                    systemImage: item.watchlisted ? "minus.circle" : "plus.circle"
                                )
                            }
                        }
                        .onAppear {
                            Task { await viewModel.loadNextPageIfNeeded(current: item.id, environment: environment) }
                        }
                    }
                }

                if !viewModel.statusMessage.isEmpty {
                    Text(viewModel.statusMessage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .navigationTitle("Library")
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
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(viewModel.sections) { section in
                    Button {
                        Task { await viewModel.select(section, environment: environment) }
                    } label: {
                        Text(section.rawValue)
                    }
                    .buttonStyle(.plain)
                    .crispyChip(isSelected: section == viewModel.selectedSection)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
