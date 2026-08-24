import SwiftUI

/// Search page mirroring the Android `SearchScreen`: glass search field,
/// recent history + suggestion chips, results grid.
struct SearchScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = SearchViewModel()
    @FocusState private var fieldFocused: Bool

    private let columns = [GridItem(.adaptive(minimum: 240), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                searchField

                if viewModel.results.isEmpty && !viewModel.suggestions.isEmpty {
                    Text("Suggestions")
                        .font(.headline)
                    chipsRow(items: viewModel.suggestions.map(\.title)) { title in
                        if let match = viewModel.suggestions.first(where: { $0.title == title }) {
                            Task { await viewModel.submitSuggestion(match, environment: environment) }
                            fieldFocused = false
                        }
                    }
                }

                if viewModel.results.isEmpty && !viewModel.history.isEmpty {
                    HStack {
                        Text("Recent searches").font(.headline)
                        Spacer()
                        Button("Clear") { viewModel.clearHistory() }
                            .font(.footnote)
                    }
                    chipsRow(items: viewModel.history) { query in
                        viewModel.updateQuery(query, environment: environment)
                        Task { await viewModel.submit(environment: environment) }
                        fieldFocused = false
                    }
                }

                if viewModel.isSearching {
                    ProgressView()
                        .padding(.top, 24)
                }

                if let status = viewModel.statusMessage.nilIfBlank {
                    Text(status)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if !viewModel.results.isEmpty {
                    Text("Results")
                        .font(.headline)
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(viewModel.results) { item in
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
                        }
                    }
                }

                if viewModel.results.isEmpty && viewModel.suggestions.isEmpty && viewModel.query.isEmpty && viewModel.history.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("Search movies and shows")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 64)
                }
            }
            .padding(16)
            .padding(.bottom, 24)
        }
        .navigationTitle("Search")
        .navigationBarTitleDisplayMode(.inline)
        .task { viewModel.loadHistoryIfNeeded() }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Movies, shows, people", text: Binding(
                get: { viewModel.query },
                set: { viewModel.updateQuery($0, environment: environment) }
            ))
            .focused($fieldFocused)
            .submitLabel(.search)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .onSubmit {
                Task { await viewModel.submit(environment: environment) }
                fieldFocused = false
            }
            if !viewModel.query.isEmpty {
                Button {
                    viewModel.updateQuery("", environment: environment)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .glassEffect(in: .capsule)
    }

    private func chipsRow(items: [String], onTap: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                ForEach(items, id: \.self) { item in
                    Button(item) { onTap(item) }
                        .buttonStyle(.plain)
                        .crispyChip()
                }
            }
        }
    }
}
