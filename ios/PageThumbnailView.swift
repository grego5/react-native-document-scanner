import UIKit

/// Image observation and display only; no touch handling, ordering, selection or JS
/// callbacks. Tiles read the page's thumbnail or retained frame, never its original or
/// full image, at most at their own pixel size. Main only.
@MainActor
final class PageThumbnailView: UIView {
  typealias Decode = @Sendable (SessionPageSource, Int) throws -> CGImage

  private let imageView = UIImageView()
  private let decode: Decode
  private var state: ScanSessionState?
  private var pageId: String?
  private var binding: SessionPageBinding?
  private var source: SessionPageSource?
  /// The pin behind the shown image.
  private var displayedSource: SessionPageSource?
  private let generation = LoadGeneration()
  private var loadedSide = 0
  private var disposed = false

  var image: CGImage? { imageView.image?.cgImage }

  init(decode: @escaping Decode = { try PageThumbnails.shared.tile($0, longSide: $1) }) {
    self.decode = decode
    super.init(frame: .zero)
    clipsToBounds = true
    imageView.frame = bounds
    imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    imageView.contentMode = .scaleAspectFill
    addSubview(imageView)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

  func bind(_ next: ScanSessionState?, _ id: String, mode: PageThumbnailResizeMode) {
    guard !disposed else { return }
    imageView.contentMode = mode == .contain ? .scaleAspectFit : .scaleAspectFill
    if state === next && pageId == id { return }
    binding?.close()
    source?.close(); source = nil
    clearImage()
    state = next; pageId = id
    binding = next.map { owner in
      SessionPageBinding(state: owner, id: id, thumbnailOnly: true, changed: { [weak self] value in
        guard let self else { value?.close(); return }
        source?.close(); source = value
        if value == nil { clearImage() } else { load() }
      }, failed: { _ in })
    }
    if window != nil { binding?.start() }
  }

  /// The tile's long side in pixels.
  private var pixelSide: Int {
    Int((max(bounds.width, bounds.height) * (window?.screen.scale ?? traitCollection.displayScale)).rounded(.up))
  }

  private func load() {
    let current = generation.advance()
    let side = pixelSide
    guard let value = source, window != nil, side > 0 else { return }
    loadedSide = side
    let decode = decode
    ScannerImageReads.read(value.retain(), generation: generation, current, { try decode($0, side) }) { [weak self] result in
      guard let self, !disposed, generation.isCurrent(current), binding?.valid == true,
            case .success(let pixels) = result else { return }
      displayedSource?.close()
      displayedSource = value.retain()
      imageView.image = UIImage(cgImage: pixels.image)
    }
  }

  private func clearImage() {
    generation.advance()
    loadedSide = 0
    displayedSource?.close(); displayedSource = nil
    imageView.image = nil
  }

  func dispose() {
    guard !disposed else { return }
    disposed = true
    binding?.close(); binding = nil
    source?.close(); source = nil
    state = nil; pageId = nil
    clearImage()
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window != nil {
      if !disposed { binding?.start() }
    } else {
      binding?.close()
      source?.close(); source = nil
      clearImage()
    }
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    // A larger tile decodes again; a smaller one scales the shown image down.
    if source != nil, pixelSide > loadedSide { load() }
  }
}
