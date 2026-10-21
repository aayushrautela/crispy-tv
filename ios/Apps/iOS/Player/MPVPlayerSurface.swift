import SwiftUI
import UIKit

/// The SwiftUI surface the engine renders into.
struct MPVPlayerSurface: UIViewRepresentable {
    let engine: MPVPlaybackEngine

    func makeUIView(context: Context) -> MPVPlayerView {
        MPVPlayerView(metalLayer: engine.metalLayer)
    }

    func updateUIView(_ uiView: MPVPlayerView, context: Context) {}
}

/// Hosts the engine's Metal layer.
///
/// mpv is bound to the layer and not to the view, so the view's whole job is
/// to keep the layer on screen and the size of the view.
final class MPVPlayerView: UIView {
    private let metalLayer: CAMetalLayer

    init(metalLayer: CAMetalLayer) {
        self.metalLayer = metalLayer
        super.init(frame: .zero)
        backgroundColor = .black
        layer.addSublayer(metalLayer)
    }

    required init?(coder: NSCoder) {
        // The surface is created in code, never from a storyboard.
        return nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // The layer is animated with the view's bounds by default, and the
        // animation is not wanted here.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.frame = bounds
        metalLayer.contentsScale = window?.screen.nativeScale ?? UIScreen.main.nativeScale
        CATransaction.commit()
    }
}
