import SwiftUI

/// Layout constants mirroring `com.crispy.tv.ui.theme.Dimensions`.
enum Theme {
    static let cardCornerRadius: CGFloat = 16
    static let chipHeight: CGFloat = 32
    static let sectionSpacing: CGFloat = 28
    static let railSpacing: CGFloat = 12

    static let landscapeCardWidth: CGFloat = 248
    static let landscapeAspectRatio: CGFloat = 16.0 / 9.0

    static let avatarSize: CGFloat = 30

    static func pageHorizontalPadding(for width: CGFloat) -> CGFloat {
        switch width {
        case ..<768: return 16
        case ..<1024: return 24
        default: return 32
        }
    }

    /// Spinner color mirroring `com.crispy.tv.ui.theme.CrispySpinner`.
    static let spinner = Color(red: 0.961, green: 0.431, blue: 0.235)
}

extension View {
    /// Capsule chip matching the Android FilterChip styling used on Home/Discover/Library.
    func crispyChip(isSelected: Bool = false) -> some View {
        let glass: Glass = isSelected
            ? .regular.tint(Color.primary.opacity(0.35))
            : .regular
        return self
            .font(.subheadline.weight(isSelected ? .semibold : .medium))
            .foregroundStyle(Color.primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .glassEffect(glass.interactive(), in: .capsule)
    }
}
