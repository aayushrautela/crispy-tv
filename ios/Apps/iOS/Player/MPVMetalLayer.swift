import QuartzCore
import UIKit

/// The `CAMetalLayer` mpv renders into.
///
/// Two MoltenVK workarounds live here, and both have to stay:
///
/// - MoltenVK writes a 1x1 drawable size to force a presentation. Forwarding
///   it makes the surface flicker and can leave the layer stuck at 1x1, so
///   sizes below 2x2 are dropped.
/// - `wantsExtendedDynamicRangeContent` has to be set on the main thread.
///   mpv's video output sets it from its own thread while it holds the core
///   lock, and the setter only takes effect on the main thread.
final class MPVMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            guard newValue.width > 1, newValue.height > 1 else { return }
            super.drawableSize = newValue
        }
    }

    override var wantsExtendedDynamicRangeContent: Bool {
        get { super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                // The setter re-enters this property, and the main thread
                // takes the branch above, so the value reaches the layer once.
                DispatchQueue.main.async { [weak self] in
                    self?.wantsExtendedDynamicRangeContent = newValue
                }
            }
        }
    }
}
