import CrispyKit
import SwiftUI

/// The languages a preference can be set to, plus "Automatic".
///
/// The list is `LanguageTags.selectableLanguages`, so a language can only be
/// chosen if the matcher that reads it back knows it.
struct LanguagePickerScreen: View {
    /// The code that is selected now, or `nil` for Automatic.
    let selection: String?
    /// Called with the chosen code, or `nil` for Automatic.
    let onSelect: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var options: [LanguageOption] {
        let all = LanguageTags.selectableLanguages
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return all }
        return all.filter {
            $0.name.localizedCaseInsensitiveContains(needle)
                || $0.code.localizedCaseInsensitiveContains(needle)
        }
    }

    var body: some View {
        List {
            Button {
                choose(nil)
            } label: {
                row(title: "Automatic", code: nil)
            }
            ForEach(options) { option in
                Button {
                    choose(option.code)
                } label: {
                    row(title: option.name, code: option.code)
                }
            }
        }
        .searchable(text: $query, prompt: "Language or code")
        .navigationTitle("Language")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func choose(_ code: String?) {
        onSelect(code)
        dismiss()
    }

    private func row(title: String, code: String?) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(.primary)
            Spacer()
            if selection == code {
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.spinner)
            }
        }
    }
}
