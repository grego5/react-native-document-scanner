import AVFoundation
import UIKit

/// The camera view's UIKit host: a black view with the preview layer placed over the framed
/// region, aspect-filled, so a square frame shows only the centered square, with the live guide
/// above it. Main only.
final class CameraPreviewView: UIView {
  let previewLayer = AVCaptureVideoPreviewLayer()
  let guide = LiveGuideOverlay()
  lazy var feedback = CaptureFeedbackOverlay(guide: guide)
  private let frozen = UIImageView()
  private let statsPanel = UIScrollView()
  private let statsLabel = UILabel()
  private var statsContentSize = CGSize.zero
  private var frozenID: String?
  var onWindowChange: (() -> Void)?
  var onResize: (() -> Void)?
  /// A tap on the shown preview, as an AVFoundation device point (unit sensor coordinates).
  var onTap: ((CGPoint) -> Void)?

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
    frozen.contentMode = .scaleAspectFit
    frozen.backgroundColor = .black
    frozen.isHidden = true
    addSubview(frozen)
    addSubview(guide)
    addSubview(feedback)
    statsPanel.backgroundColor = UIColor(white: 0.07, alpha: 0.9)
    statsPanel.isHidden = true
    statsLabel.textColor = .white
    statsLabel.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
    statsLabel.numberOfLines = 0
    statsLabel.lineBreakMode = .byClipping
    statsPanel.addSubview(statsLabel)
    addSubview(statsPanel)
    addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
  }

  func showStats(_ rows: [String]?) {
    statsPanel.isHidden = rows == nil
    guard let rows else { return }
    let text = rows.joined(separator: "\n")
    guard text != statsLabel.text else { return }
    statsLabel.text = text
    let width = rows.map { ($0 as NSString).size(withAttributes: [.font: statsLabel.font as UIFont]).width }.max() ?? 0
    let height = ceil(statsLabel.font.lineHeight) * CGFloat(rows.count)
    statsContentSize = CGSize(width: ceil(width) + 1, height: height)
    layoutStatsPanel()
  }

  private func layoutStatsPanel() {
    let width = min(bounds.width, statsContentSize.width + 16)
    let contentHeight = statsContentSize.height + 12
    let tenRowsHeight = ceil(statsLabel.font.lineHeight) * 10 + 12
    let height = min(contentHeight, tenRowsHeight, bounds.height / 2)
    statsPanel.frame = CGRect(x: 0, y: 0, width: width, height: height)
    statsPanel.contentSize = CGSize(width: width, height: contentHeight)
    statsLabel.frame = CGRect(x: 8, y: 6, width: max(0, width - 16), height: statsContentSize.height)
  }

  @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
    let location = recognizer.location(in: self)
    guard recognizer.state == .ended,
          statsPanel.isHidden || !statsPanel.frame.contains(location),
          let point = devicePoint(location) else { return }
    onTap?(point)
  }

  /// The device point under a view point; nil outside the shown preview. The layer converts through
  /// its own rotation and aspect-fill crop.
  func devicePoint(_ viewPoint: CGPoint) -> CGPoint? {
    guard !previewLayer.isHidden, previewLayer.frame.contains(viewPoint) else { return nil }
    let point = previewLayer.captureDevicePointConverted(fromLayerPoint: previewLayer.convert(viewPoint, from: layer))
    guard point.x.isFinite, point.y.isFinite, (0...1).contains(point.x), (0...1).contains(point.y) else { return nil }
    return point
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
    frozen.frame = bounds
    guide.frame = bounds
    feedback.frame = bounds
    layoutStatsPanel()
    place()
    if bounds.size != placedSize {
      placedSize = bounds.size
      onResize?()
    }
  }

  func freeze(_ image: CGImage, rotation: Int, id: String) {
    frozenID = id
    let orientation: UIImage.Orientation
    switch rotation {
    case 90: orientation = .right
    case 180: orientation = .down
    case 270: orientation = .left
    default: orientation = .up
    }
    frozen.image = UIImage(cgImage: image, scale: 1, orientation: orientation)
    frozen.isHidden = false
    guide.isHidden = true
  }

  func clearFrozen(id: String? = nil) {
    guard id == nil || id == frozenID else { return }
    frozenID = nil
    frozen.image = nil
    frozen.isHidden = true
    guide.isHidden = false
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
