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

    /// Yellow accent from the Android dark fallback palette.
    static let accent = Color(red: 1.0, green: 0.769, blue: 0.0)
}

extension View {
    /// Capsule chip matching the Android FilterChip styling used on Home/Discover/Library.
    func crispyChip(isSelected: Bool = false) -> some View {
        let glass: Glass = isSelected
            ? .regular.tint(Theme.accent.opacity(0.35))
            : .regular
        return self
            .font(.subheadline.weight(isSelected ? .semibold : .medium))
            .foregroundStyle(isSelected ? Theme.accent : Color.primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .glassEffect(glass.interactive(), in: .capsule)
    }
}
