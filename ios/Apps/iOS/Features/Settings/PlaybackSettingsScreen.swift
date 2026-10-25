import CrispyKit
import SwiftUI

/// The playback preferences the player and the stream picker honour.
///
/// The screen writes the same store the player writes: a language chosen here
/// and a language remembered from a track sheet are the same preference in the
/// same place, so neither can quietly disagree with the other.
///
/// Only what iOS has a consumer for is here. Skip-intro controls, trailer
/// autoplay, the libass render type, the engine choice and image quality are
/// Android settings whose iOS counterparts do not exist yet, and a switch
/// nothing reads is a switch that lies — see `docs/plans/ios-mpv-player-plan.md`.
struct PlaybackSettingsScreen: View {
    @Environment(AppEnvironment.self) private var environment

    /// The rates the picker offers. mpv accepts any positive rate; these are
    /// the ones a viewer actually asks for.
    private static let speeds: [Double] = [0.5, 0.75, 1, 1.25, 1.5, 2]

    private var store: PlaybackSettingsStore { environment.playbackSettings }

    var body: some View {
        List {
            Section {
                languageRow(
                    title: "Default audio language",
                    code: store.settings.defaultAudioLanguage,
                    select: { store.setDefaultAudioLanguage($0) }
                )
                languageRow(
                    title: "Default subtitle language",
                    code: store.settings.defaultSubtitleLanguage,
                    select: { store.setDefaultSubtitleLanguage($0) }
                )
            } header: {
                Text("Languages")
            } footer: {
                Text("Used when a source is opened. Picking a track while watching updates these.")
            }

            Section {
                speedRow
                Toggle(
                    "Auto-select stream",
                    isOn: Binding(
                        get: { store.settings.autoSelectStream },
                        set: { store.setAutoSelectStream($0) }
                    )
                )
            } header: {
                Text("Playback")
            } footer: {
                Text("Auto-select plays the best source the addons offer as soon as it is found, instead of waiting for a tap.")
            }
        }
        .navigationTitle("Playback")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var speedRow: some View {
        Menu {
            ForEach(Self.speeds, id: \.self) { speed in
                Button(speedLabel(speed)) {
                    store.setPlaybackSpeed(speed)
                }
            }
        } label: {
            HStack {
                Text("Playback speed")
                Spacer()
                Text(speedLabel(store.settings.playbackSpeed))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func languageRow(
        title: String,
        code: String?,
        select: @escaping (String?) -> Void
    ) -> some View {
        NavigationLink {
            LanguagePickerScreen(selection: code, onSelect: select)
        } label: {
            HStack {
                Text(title)
                Spacer()
                Text(code.map { LanguageTags.label(for: $0) } ?? "Automatic")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func speedLabel(_ speed: Double) -> String {
        String(format: "%g×", speed)
    }
}
