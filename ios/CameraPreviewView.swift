import AVFoundation
import UIKit

/// The camera view's UIKit host: a black view with the preview layer placed over the framed
/// region, aspect-filled, so a square frame shows only the centered square, with the live guide
/// above it. Main only.
final class CameraPreviewView: UIView {
  let previewLayer = AVCaptureVideoPreviewLayer()
  let guide = LiveGuideOverlay()
  var onWindowChange: (() -> Void)?
  var onResize: (() -> Void)?

  /// The configured video size, in sensor axes; nil hides the preview.
  var video: PixelSize? { didSet { if video != oldValue { place() } } }
  /// AVFoundation's clockwise preview rotation angle.
  var rotationAngle: Double = 0 { didSet { if rotationAngle != oldValue { rotate(); place() } } }
  var square = false { didSet { if square != oldValue { guide.square = square; place() } } }
  private var placedSize = CGSize.zero

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .black
    clipsToBounds = true
    previewLayer.videoGravity = .resizeAspectFill
    previewLayer.isHidden = true
    layer.addSublayer(previewLayer)
    addSubview(guide)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  /// The framing the preview shows; nil until a video size is configured.
  var framing: CameraFraming? {
    video.flatMap { CameraFraming(buffer: $0, rotationAngle: rotationAngle, square: square) }
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    onWindowChange?()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    guide.frame = bounds
    place()
    if bounds.size != placedSize {
      placedSize = bounds.size
      onResize?()
    }
  }

  /// Applies the angle to the preview connection, which exists once the session has an input.
  func rotate() {
    guard let connection = previewLayer.connection,
          connection.isVideoRotationAngleSupported(CGFloat(rotationAngle)) else { return }
    connection.videoRotationAngle = CGFloat(rotationAngle)
  }

  private func place() {
    let framing = framing
    guide.previewFraming = framing
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    if let framing {
      previewLayer.frame = framing.presentation(in: bounds)
      previewLayer.isHidden = false
    } else {
      previewLayer.isHidden = true
    }
    CATransaction.commit()
  }
}
