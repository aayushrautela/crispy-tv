import CrispyKit
import SwiftUI

/// The track picker: the audio and subtitle tracks the media carries.
struct PlayerTrackSheet: View {
    let tracks: [PlaybackTrack]
    let onSelect: (PlaybackTrackSelection) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(PlaybackTrackKind.allCases, id: \.self) { kind in
                    let kindTracks = tracks.filter { $0.kind == kind }
                    if !kindTracks.isEmpty {
                        Section(sectionTitle(kind)) {
                            if kind == .subtitle {
                                Button {
                                    onSelect(.disabled(.subtitle))
                                    dismiss()
                                } label: {
                                    row(title: "Off", isSelected: !hasSelectedSubtitle)
                                }
                            }
                            ForEach(kindTracks) { track in
                                Button {
                                    onSelect(.track(track))
                                    dismiss()
                                } label: {
                                    row(title: trackTitle(track), isSelected: track.isSelected)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Tracks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func row(title: String, isSelected: Bool) -> some View {
        HStack {
            Text(title)
            Spacer()
            if isSelected {
                Image(systemName: "checkmark")
            }
        }
        .contentShape(Rectangle())
    }

    private func sectionTitle(_ kind: PlaybackTrackKind) -> String {
        switch kind {
        case .audio: return "Audio"
        case .video: return "Video"
        case .subtitle: return "Subtitles"
        }
    }

    private func trackTitle(_ track: PlaybackTrack) -> String {
        var title = track.title ?? track.language ?? "Track \(track.id)"
        if track.isForced {
            title += " (forced)"
        }
        return title
    }

    private var hasSelectedSubtitle: Bool {
        tracks.contains { $0.kind == .subtitle && $0.isSelected }
    }
}
