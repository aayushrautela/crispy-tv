import CrispyKit
import SwiftUI

/// Home page mirroring the Android `HomeRoute`: header pills, hero carousel,
/// Continue Watching wide rail, then planned catalog rails.
struct HomeScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = HomeViewModel()
    @State private var showProfileMenu = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.sectionSpacing) {
                if viewModel.isLoading && viewModel.rails.isEmpty && viewModel.heroItems.isEmpty {
                    skeleton
                } else if let message = statusOrEmptyMessage {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 16)
                        .padding(.top, 32)
                } else {
                    content
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .backgroundExtensionEffect()
        .toolbar {
            ToolbarItem(placement: .principal) {
                CrispyWordmark()
            }
            ToolbarItem(placement: .topBarTrailing) {
                ProfileMenuButton(profile: nil) {
                    showProfileMenu = true
                }
            }
        }
        .refreshable { await viewModel.load(environment: environment) }
        .task { await viewModel.loadIfNeeded(environment: environment) }
        .sheet(isPresented: $showProfileMenu) {
            ProfileMenuSheet(onDismissed: { Task { await viewModel.load(environment: environment) } })
        }
    }

    private var statusOrEmptyMessage: String? {
        if !viewModel.statusMessage.isEmpty { return viewModel.statusMessage }
        if viewModel.rails.isEmpty && viewModel.continueWatchingItems.isEmpty { return "No recommendations available right now." }
        return nil
    }

    @ViewBuilder
    private var content: some View {
        if !viewModel.pills.isEmpty {
            headerPills
        }

        if !viewModel.heroItems.isEmpty {
            heroCarousel
        }

        if !viewModel.continueWatchingItems.isEmpty {
            RailSectionView(title: "Continue watching") {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: Theme.railSpacing) {
                        ForEach(viewModel.continueWatchingItems) { item in
                            NavigationLink(value: item.detailsRoute) {
                                LandscapeCardView(
                                    title: item.title,
                                    artworkUrl: item.artworkUrl,
                                    logoUrl: nil,
                                    ratingText: nil,
                                    yearText: nil,
                                    maturityRating: nil,
                                    genre: nil,
                                    badge: nil,
                                    progressPercent: item.progressPercent
                                )
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button(role: .destructive) {
                                    Task { await viewModel.dismissContinueWatching(item, environment: environment) }
                                } label: {
                                    Label("Remove from Continue watching", systemImage: "xmark.circle")
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }

        if !viewModel.upNextItems.isEmpty {
            RailSectionView(title: "Up next") {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: Theme.railSpacing) {
                        ForEach(viewModel.upNextItems) { entry in
                            NavigationLink(value: AppRoute.details(itemId: entry.showItemId, itemType: "show")) {
                                LandscapeCardView(
                                    title: entry.showTitle,
                                    artworkUrl: entry.artworkUrl,
                                    logoUrl: entry.logoUrl,
                                    ratingText: nil,
                                    yearText: nil,
                                    maturityRating: nil,
                                    genre: nil,
                                    badge: entry.badge
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }

        if !viewModel.thisWeekItems.isEmpty {
            RailSectionView(title: "This week") {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: Theme.railSpacing) {
                        ForEach(viewModel.thisWeekItems) { entry in
                            NavigationLink(value: entry.card.detailsRoute) {
                                LandscapeCardView(
                                    title: entry.card.title,
                                    artworkUrl: entry.card.artworkUrl,
                                    logoUrl: entry.card.logoUrl,
                                    ratingText: entry.card.ratingText,
                                    yearText: nil,
                                    maturityRating: nil,
                                    genre: nil,
                                    badge: entry.badge
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }

        ForEach(viewModel.rails) { rail in
            RailSectionView(
                title: rail.title,
                seeAllRoute: AppRoute.catalogList(catalogId: rail.catalogId, title: rail.title)
            ) {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: Theme.railSpacing) {
                        ForEach(rail.items) { item in
                            NavigationLink(value: item.detailsRoute) {
                                LandscapeCardView(
                                    title: item.title,
                                    artworkUrl: item.artworkUrl,
                                    logoUrl: item.logoUrl,
                                    ratingText: item.ratingText,
                                    yearText: item.yearText,
                                    maturityRating: item.maturityRating,
                                    genre: item.genre
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }

    /// Header category chips (pill sections from the planner), matching the
    /// Android `HomeHeaderSectionChips`.
    private var headerPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                ForEach(viewModel.pills) { pill in
                    NavigationLink(value: AppRoute.catalogList(catalogId: pill.catalogId, title: pill.title)) {
                        Text(pill.title)
                    }
                    .buttonStyle(.plain)
                    .crispyChip()
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var heroCarousel: some View {
        TabView {
            ForEach(viewModel.heroItems) { hero in
                NavigationLink(value: AppRoute.details(itemId: hero.mediaKey, itemType: hero.type)) {
                    HeroCardView(hero: hero)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .automatic))
        .frame(height: 430)
    }

    private var skeleton: some View {
        VStack(alignment: .leading, spacing: Theme.sectionSpacing) {
            RoundedRectangle(cornerRadius: Theme.cardCornerRadius)
                .fill(Color(.secondarySystemBackground))
                .aspectRatio(Theme.landscapeAspectRatio, contentMode: .fit)
                .padding(.horizontal, 16)
            ForEach(0..<2, id: \.self) { _ in
                VStack(alignment: .leading, spacing: 10) {
                    Text("")
                        .font(.title3.weight(.semibold))
                        .redacted(reason: .placeholder)
                    HStack(spacing: Theme.railSpacing) {
                        ForEach(0..<3, id: \.self) { _ in
                            RoundedRectangle(cornerRadius: Theme.cardCornerRadius)
                                .fill(Color(.secondarySystemBackground))
                                .frame(width: Theme.landscapeCardWidth)
                                .aspectRatio(Theme.landscapeAspectRatio, contentMode: .fit)
                        }
                    }
                    .redacted(reason: .placeholder)
                    .padding(.horizontal, 16)
                }
            }
        }
    }
}

/// Full-bleed hero slide with glass CTA row, port of `HomeHeroCarousel` visuals.
struct HeroCardView: View {
    let hero: HomeViewModel.HeroItem

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: hero.artworkUrl)

            LinearGradient(
                colors: [.clear, .black.opacity(0.75)],
                startPoint: .center,
                endPoint: .bottom
            )

            VStack(alignment: .leading, spacing: 10) {
                Text(hero.title)
                    .font(.title.weight(.bold))
                    .lineLimit(2)

                if !metaLine.isEmpty {
                    Text(metaLine)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.85))
                }

                if !hero.descriptionText.isEmpty {
                    Text(hero.descriptionText)
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.75))
                        .lineLimit(2)
                }

                GlassEffectContainer(spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: "play.fill")
                            .font(.body.weight(.semibold))
                            .padding(14)
                            .glassEffect(.regular.tint(.white.opacity(0.9)), in: .circle)
                        Image(systemName: "info")
                            .font(.body.weight(.semibold))
                            .padding(14)
                            .glassEffect(in: .circle)
                    }
                }
            }
            .padding(20)
        }
        .clipShape(.rect(cornerRadius: 24))
    }

    private var metaLine: String {
        [hero.yearText, hero.ratingText.map { "★ \($0)" }, hero.genres.first]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }
}
