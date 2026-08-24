import CrispyKit
import SwiftUI

/// Title details page, port of the Android `DetailsScreen` (hero, meta,
/// watch CTA, overview, cast, seasons/episodes, similar).
struct DetailsScreen: View {
    let itemId: String
    let itemType: String

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel: DetailsViewModel?
    @State private var showPlaybackNotice = false

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
        .toolbarBackgroundVisibility(.visible, for: .navigationBar)
        .task {
            let vm = ensureViewModel()
            await vm.loadIfNeeded(environment: environment)
        }
    }

    private func ensureViewModel() -> DetailsViewModel {
        if let viewModel { return viewModel }
        let created = DetailsViewModel(itemId: itemId, itemType: itemType)
        viewModel = created
        return created
    }

    @ViewBuilder
    private func content(_ viewModel: DetailsViewModel) -> some View {
        if viewModel.isLoadingDetail && viewModel.detail == nil {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let detail = viewModel.detail {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.sectionSpacing) {
                    hero(detail)

                    VStack(alignment: .leading, spacing: 16) {
                        metaLine(detail)
                        watchCta(viewModel)
                        genresRow(detail)
                        if let overview = detail.item.overview.nilIfBlank {
                            Text(overview)
                                .font(.body)
                                .foregroundStyle(.primary.opacity(0.9))
                        }
                        creditsRow(detail)
                    }
                    .padding(.horizontal, 16)

                    if viewModel.isShow && !viewModel.seasons.isEmpty {
                        episodesSection(viewModel)
                            .padding(.horizontal, 16)
                    }

                    if !viewModel.similar.isEmpty {
                        RailSectionView(title: "More like this") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: Theme.railSpacing) {
                                    ForEach(viewModel.similar) { item in
                                        NavigationLink(value: item.detailsRoute) {
                                            LandscapeCardView(
                                                title: item.title,
                                                backdropUrl: item.backdropUrl ?? item.posterUrl,
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

                    castRail(detail)

                    if !viewModel.errorMessage.isEmpty {
                        Text(viewModel.errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 16)
                    }
                }
                .padding(.bottom, 24)
            }
            .refreshable { await viewModel.load(environment: environment) }
        } else {
            Text(viewModel.errorMessage.nilIfBlank ?? "Could not load details.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func hero(_ detail: MetadataTitleDetail) -> some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: detail.item.images.backdropUrl ?? detail.item.images.posterUrl)

            LinearGradient(
                colors: [.clear, .black.opacity(0.8)],
                startPoint: .center,
                endPoint: .bottom
            )

            Group {
                if let logoUrl = detail.item.images.logoUrl.nilIfBlank {
                    RemoteImage(url: logoUrl)
                        .aspectRatio(contentMode: .fit)
                        .frame(height: 56, alignment: .leading)
                        .frame(maxWidth: 260, alignment: .leading)
                } else {
                    Text(detail.item.title)
                        .font(.largeTitle.weight(.bold))
                        .lineLimit(3)
                }
            }
            .padding(20)
        }
        .frame(height: 320)
        .clipShape(.rect(cornerRadius: 0))
        .ignoresSafeArea(edges: .top)
    }

    @ViewBuilder
    private func metaLine(_ detail: MetadataTitleDetail) -> some View {
        let parts = [
            detail.item.releaseYear.map(String.init),
            detail.item.certification ?? detail.item.status,
            detail.item.runtimeMinutes.map { "\($0)m" },
            formatRating(detail.item.rating).map { "★ \($0)" },
        ].compactMap { $0 }.filter { !$0.isEmpty }
        if !parts.isEmpty {
            Text(parts.joined(separator: " · "))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func watchCta(_ viewModel: DetailsViewModel) -> some View {
        Button {
            showPlaybackNotice = true
        } label: {
            Label("Play", systemImage: "play.fill")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
        .buttonStyle(.glassProminent)
        .alert("Playback", isPresented: $showPlaybackNotice) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("The player arrives with the next milestone.")
        }
    }

    @ViewBuilder
    private func genresRow(_ detail: MetadataTitleDetail) -> some View {
        if !detail.item.genres.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(detail.item.genres, id: \.self) { genre in
                        Text(genre)
                            .crispyChip()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func creditsRow(_ detail: MetadataTitleDetail) -> some View {
        let directorNames = detail.directors.map(\.name)
        let creatorNames = detail.creators.map(\.name)
        if !directorNames.isEmpty || !creatorNames.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                if !directorNames.isEmpty {
                    Text("Director: \(directorNames.joined(separator: ", "))")
                }
                if !creatorNames.isEmpty {
                    Text("Creator: \(creatorNames.joined(separator: ", "))")
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func episodesSection(_ viewModel: DetailsViewModel) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("Season", selection: Binding(
                get: { viewModel.selectedSeasonNumber ?? viewModel.seasons.first?.seasonNumber ?? 1 },
                set: { newValue in
                    Task { await viewModel.selectSeason(newValue, environment: environment) }
                }
            )) {
                ForEach(viewModel.seasons) { season in
                    Text(season.displayTitle).tag(season.seasonNumber)
                }
            }
            .pickerStyle(.menu)

            if viewModel.isLoadingEpisodes {
                ProgressView()
                    .padding(.vertical, 24)
                    .frame(maxWidth: .infinity)
            } else {
                LazyVStack(alignment: .leading, spacing: 14) {
                    ForEach(viewModel.episodes) { episode in
                        EpisodeRow(episode: episode)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func castRail(_ detail: MetadataTitleDetail) -> some View {
        if !detail.cast.isEmpty {
            RailSectionView(title: "Cast") {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 16) {
                        ForEach(detail.cast) { member in
                            NavigationLink(value: AppRoute.person(personId: member.personId, profileUrl: member.profileUrl)) {
                                CastMemberCell(member: member)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }
}

struct EpisodeRow: View {
    let episode: MetadataEpisode

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: episode.stillUrl)
                    .aspectRatio(Theme.landscapeAspectRatio, contentMode: .fit)
                    .clipShape(.rect(cornerRadius: 10))
                if let number = episode.episodeNumber {
                    Text("\(number)")
                        .font(.caption.weight(.bold))
                        .padding(5)
                        .background(.black.opacity(0.65), in: .rect(cornerRadius: 6))
                        .foregroundStyle(.white)
                        .padding(4)
                }
            }
            .frame(width: 140)

            VStack(alignment: .leading, spacing: 4) {
                Text(episode.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if let airDate = episode.airDate.nilIfBlank {
                    Text(airDate)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                if let summary = episode.summary.nilIfBlank {
                    Text(summary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
    }
}

struct CastMemberCell: View {
    let member: MetadataPersonRef

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle().fill(Color(.tertiarySystemFill))
                if let url = member.profileUrl.nilIfBlank {
                    RemoteImage(url: url)
                        .clipShape(.circle)
                } else {
                    Image(systemName: "person.fill")
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 72, height: 72)

            Text(member.name)
                .font(.caption.weight(.medium))
                .lineLimit(2)
                .multilineTextAlignment(.center)
            if let role = member.role.nilIfBlank {
                Text(role)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: 88)
    }
}
