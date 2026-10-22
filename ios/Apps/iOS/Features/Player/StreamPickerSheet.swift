import CrispyKit
import SwiftUI

/// What the player is asked to play.
struct PlaybackRequest: Identifiable {
    let id = UUID()
    let source: PlaybackSource
    let identity: PlaybackIdentity
    let title: String
}

/// The title the picker resolves.
struct StreamRequest: Identifiable {
    let id = UUID()
    let mediaType: StreamMediaType
    let lookupId: String
    let identity: PlaybackIdentity
    let title: String
}

/// The streams the account's addons offer for a title.
///
/// The picker is where the streams become a choice: the addons answer with
/// every file they have, and the user picks the one they want. The player
/// opens over the picker, so the choice and the playback are one gesture.
struct StreamPickerSheet: View {
    let request: StreamRequest

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: StreamPickerViewModel?
    @State private var playing: PlaybackRequest?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Play")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
        }
        .fullScreenCover(item: $playing) { playback in
            MPVPlayerScreen(
                source: playback.source,
                title: playback.title,
                identity: playback.identity
            )
        }
        .task { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel?.isLoading == true {
            ProgressView()
                .tint(Theme.spinner)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = viewModel?.errorMessage, !error.isEmpty {
            ContentUnavailableView(
                "Nothing to play",
                systemImage: "exclamationmark.triangle",
                description: Text(error)
            )
        } else {
            List {
                ForEach(viewModel?.streams ?? []) { stream in
                    Button { play(stream) } label: { row(stream) }
                        .buttonStyle(.plain)
                }
            }
        }
    }

    private func row(_ stream: StreamSource) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(stream.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            Text(stream.addonName)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
    }

    private func load() async {
        let model: StreamPickerViewModel
        if let viewModel {
            model = viewModel
        } else {
            let created = StreamPickerViewModel()
            viewModel = created
            model = created
        }
        await model.load(
            mediaType: request.mediaType,
            lookupId: request.lookupId,
            environment: environment
        )
    }

    private func play(_ stream: StreamSource) {
        guard let source = stream.playbackSource else { return }
        playing = PlaybackRequest(
            source: source,
            identity: request.identity,
            title: request.title
        )
    }
}
