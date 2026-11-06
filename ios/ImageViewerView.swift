import UIKit

/// Shows a contain-fitted upright image that pinches and pans up to `maxZoom`; a double
/// tap zooms to `doubleTapZoom` around the tapped point, or back to the fit. The pan
/// stops at the image's edges. A new image can fade in over the shown one. Main only.
@MainActor
final class ImageViewerView: UIView, UIScrollViewDelegate {
  static let maxZoom: CGFloat = 5
  static let doubleTapZoom: CGFloat = 2.5
  private static let fadeDuration: TimeInterval = 0.18

  /// Called when `zoomScale` changes, by a gesture, an animation or a reset.
  var onZoom: ((CGFloat) -> Void)?
  /// Called when the view joins or leaves a window, as when a pager moves its page.
  var onAttach: (() -> Void)?
  var onDetach: (() -> Void)?
  /// Called when the app's UI is hidden or shown again.
  var onHidden: (() -> Void)?
  var onVisible: (() -> Void)?

  let scrollView = UIScrollView()
  let imageView = UIImageView()
  /// The previous image under a fade, kept where it was drawn.
  private var underneath: UIImageView?
  private var reportedScale: CGFloat = 1
  /// The fit size the image view was laid out for.
  private var fitSize = CGSize.zero
  /// Relayout passes through the fit; that is not a zoom change.
  private var layingOut = false

  var image: CGImage? { imageView.image?.cgImage }
  var zoomScale: CGFloat { scrollView.zoomScale }
  /// Whether the image is scaled past the fit.
  var isZoomed: Bool { scrollView.zoomScale > 1 }
  var isFading: Bool { underneath != nil }

  override init(frame: CGRect) {
    super.init(frame: frame)
    clipsToBounds = true
    scrollView.delegate = self
    scrollView.minimumZoomScale = 1
    scrollView.maximumZoomScale = Self.maxZoom
    scrollView.bounces = false
    scrollView.bouncesZoom = false
    scrollView.showsVerticalScrollIndicator = false
    scrollView.showsHorizontalScrollIndicator = false
    scrollView.contentInsetAdjustmentBehavior = .never
    scrollView.frame = bounds
    scrollView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(scrollView)
    scrollView.addSubview(imageView)
    let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
    doubleTap.numberOfTapsRequired = 2
    scrollView.addGestureRecognizer(doubleTap)
    let center = NotificationCenter.default
    center.addObserver(self, selector: #selector(appHidden), name: UIApplication.didEnterBackgroundNotification, object: nil)
    center.addObserver(self, selector: #selector(appVisible), name: UIApplication.willEnterForegroundNotification, object: nil)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

  /// Replaces the shown image in place, keeping the zoom; for the same picture.
  func replace(_ next: CGImage) {
    imageView.image = UIImage(cgImage: next)
    relayout()
  }

  /// Fits the whole image in the view again.
  func resetZoom(animated: Bool = false) {
    scrollView.setZoomScale(1, animated: animated)
    zoomUpdated()
  }

  /// Clears the image, with any fade under it, back at the fit.
  func clearImage() {
    endFade()
    imageView.image = nil
    resetZoom()
    relayout()
  }

  /// Shows `next` at the fit, fading it in over the shown image, which stays where it is
  /// drawn now until the fade ends. A detached view swaps at once.
  func fadeIn(_ next: CGImage, finished: @escaping () -> Void = {}) {
    endFade()
    let previous = imageView.image
    var under: UIImageView?
    if let previous, previous.cgImage !== next, window != nil, !bounds.isEmpty {
      let view = UIImageView(image: previous)
      view.frame = imageView.convert(imageView.bounds, to: self)
      insertSubview(view, belowSubview: scrollView)
      under = view
    }
    underneath = under
    imageView.image = UIImage(cgImage: next)
    resetZoom()
    relayout()
    guard let under else { return finished() }
    imageView.alpha = 0
    // Also runs, unfinished, when a clear or a newer fade ends this one.
    UIView.animate(withDuration: Self.fadeDuration, animations: { self.imageView.alpha = 1 }) { [weak self] _ in
      under.removeFromSuperview()
      if self?.underneath === under { self?.underneath = nil }
      finished()
    }
  }

  private func endFade() {
    imageView.layer.removeAllAnimations()
    imageView.alpha = 1
    underneath?.removeFromSuperview()
    underneath = nil
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    relayout()
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil {
      endFade()
      onDetach?()
    } else {
      onAttach?()
    }
  }

  /// Lays the image view out at the fit, keeping the zoom, which the scroll view clamps.
  private func relayout() {
    let fit = Self.fitSize(of: imageView.image?.size ?? .zero, in: bounds.size)
    if fit != fitSize {
      fitSize = fit
      let zoom = scrollView.zoomScale
      layingOut = true
      scrollView.zoomScale = 1
      imageView.frame = CGRect(origin: .zero, size: fit)
      scrollView.contentSize = fit
      scrollView.zoomScale = fit == .zero ? 1 : zoom
      layingOut = false
    }
    center()
    zoomUpdated()
  }

  static func fitSize(of image: CGSize, in view: CGSize) -> CGSize {
    guard image.width > 0, image.height > 0, view.width > 0, view.height > 0 else { return .zero }
    let scale = min(view.width / image.width, view.height / image.height)
    return CGSize(width: image.width * scale, height: image.height * scale)
  }

  /// Centers the image along the axes it does not fill.
  private func center() {
    let content = scrollView.contentSize
    let size = scrollView.bounds.size
    let x = max(0, (size.width - content.width) / 2)
    let y = max(0, (size.height - content.height) / 2)
    scrollView.contentInset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
  }

  private func zoomUpdated() {
    let scale = scrollView.zoomScale
    guard scale != reportedScale else { return }
    reportedScale = scale
    onZoom?(scale)
  }

  /// The visible rect, in the image view's fit coordinates, that zooms to `zoom` while
  /// keeping the image point under `location` (in this view) in place; UIKit clamps it.
  func zoomRect(around location: CGPoint, zoom: CGFloat) -> CGRect {
    let point = convert(location, to: imageView)
    return CGRect(x: point.x - location.x / zoom, y: point.y - location.y / zoom,
                  width: bounds.width / zoom, height: bounds.height / zoom)
  }

  /// A double tap: back to the fit when zoomed in, otherwise `doubleTapZoom` around `location`.
  func toggleZoom(at location: CGPoint, animated: Bool = true) {
    guard imageView.image != nil else { return }
    if scrollView.zoomScale > 1.01 {
      scrollView.setZoomScale(1, animated: animated)
    } else {
      scrollView.zoom(to: zoomRect(around: location, zoom: Self.doubleTapZoom), animated: animated)
    }
  }

  @objc private func doubleTapped(_ gesture: UITapGestureRecognizer) { toggleZoom(at: gesture.location(in: self)) }

  @objc private func appHidden() { onHidden?() }

  @objc private func appVisible() { onVisible?() }

  // MARK: UIScrollViewDelegate

  func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

  func scrollViewDidZoom(_ scrollView: UIScrollView) {
    guard !layingOut else { return }
    center()
    zoomUpdated()
  }
}
