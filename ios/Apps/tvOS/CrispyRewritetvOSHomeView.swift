import SwiftUI

struct CrispyRewritetvOSHomeView: View {
    var body: some View {
        VStack(spacing: 20) {
            Text("Crispy Rewrite tvOS")
                .font(.largeTitle)
            Text("Placeholder shell is active.")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
        .padding(60)
    }
}

#Preview {
    CrispyRewritetvOSHomeView()
}
