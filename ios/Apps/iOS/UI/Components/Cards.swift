import SwiftUI

/// AsyncImage wrapper standing in for Android's `CrispyImage`/Coil pipeline.
/// Uses the shared URLCache; failures fall back to a letter tile.
struct RemoteImage: View {
    let url: String?
    var contentMode: ContentMode = .fill

    var body: some View {
        if let urlString = url?.nilIfBlank, let url = URL(string: urlString) {
            AsyncImage(url: url, content: { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(contentMode: contentMode)
                case .failure:
                    Color(.secondarySystemBackground)
                case .empty:
                    ZStack {
                        Color(.secondarySystemBackground)
                        ProgressView().controlSize(.small)
                    }
                @unknown default:
                    Color(.secondarySystemBackground)
                }
            })
        } else {
            Color(.secondarySystemBackground)
        }
    }
}

/// 16:9 landscape media card mirroring the Android `LandscapeCard`:
/// backdrop, bottom scrim, logo/title, metadata line, optional badge.
struct LandscapeCardView: View {
    let title: String
    let backdropUrl: String?
    let logoUrl: String?
    let ratingText: String?
    let yearText: String?
    let maturityRating: String?
    let genre: String?
    var badge: String? = nil
    var progressPercent: Double? = nil
    var width: CGFloat? = Theme.landscapeCardWidth

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: backdropUrl)

                LinearGradient(
                    colors: [.clear, .black.opacity(0.65)],
                    startPoint: .center,
                    endPoint: .bottom
                )

                if let badge {
                    Text(badge)
                        .font(.caption2.bold())
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Theme.accent.opacity(0.9), in: .rect(cornerRadius: 8))
                        .foregroundStyle(.black)
                        .padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }

                VStack(alignment: .leading, spacing: 4) {
                    if let logoUrl = logoUrl?.nilIfBlank {
                        RemoteImage(url: logoUrl)
                            .aspectRatio(contentMode: .fit)
                            .frame(height: 30, alignment: .leading)
                            .frame(maxWidth: 160, alignment: .leading)
                    } else {
                        Text(title)
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white.opacity(0.96))
                            .lineLimit(1)
                    }
                    if !metadataParts.isEmpty {
                        Text(metadataParts.joined(separator: " · "))
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(1)
                    }
                }
                .padding(12)

                if let progressPercent, progressPercent > 0 {
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        GeometryReader { proxy in
                            ZStack(alignment: .leading) {
                                Capsule().fill(.white.opacity(0.25))
                                Capsule()
                                    .fill(Theme.accent)
                                    .frame(width: proxy.size.width * min(progressPercent / 100.0, 1.0))
                            }
                        }
                        .frame(height: 3)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 6)
                    }
                }
            }
            .aspectRatio(Theme.landscapeAspectRatio, contentMode: .fit)
            .clipShape(.rect(cornerRadius: Theme.cardCornerRadius))
        }
        .frame(width: width)
    }

    private var metadataParts: [String] {
        [yearText, maturityRating, genre, ratingText.map { "★ \($0)" }]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
    }
}

/// Horizontal rail with title + optional "See all" action, mirroring
/// the Android `CrispyShelfSection`.
struct RailSectionView<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    var seeAllRoute: AppRoute? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.title3.weight(.semibold))
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if let seeAllRoute {
                    NavigationLink(value: seeAllRoute) {
                        HStack(spacing: 4) {
                            Text("See all")
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                        }
                        .font(.footnote.weight(.medium))
                    }
                    .buttonStyle(.glass)
                    .controlSize(.small)
                }
            }
            .padding(.horizontal, 16)

            content()
        }
    }
}
