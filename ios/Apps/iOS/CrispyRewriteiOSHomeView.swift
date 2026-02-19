import SwiftUI

struct CrispyRewriteiOSHomeView: View {
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Crispy Rewrite")
                    .font(.largeTitle.weight(.semibold))
                Text("iOS placeholder target is compiling. Core parity contracts and native playback port follow next phases.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
            .navigationTitle("Home")
        }
    }
}

#Preview {
    CrispyRewriteiOSHomeView()
}
