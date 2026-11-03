import CoreGraphics

/// Loads one session page into `image`, only while the view is in a window: a detached
/// view, such as a page a pager has moved out of its window, holds no image, and shows
/// its shared thumbnail again once attached. Zooming past `preloadZoom` starts decoding
/// the full image, which replaces the thumbnail in place if the page is still zoomed
/// when it arrives and is discarded otherwise; back at the fit, the thumbnail returns.
/// A newly installed thumbnail is reported to `onLoaded`; a failure goes to `onFailed`,
/// leaves the shown image, and is retried when the view becomes visible again. The full
/// image is also dropped while the app is in the background, and decoded again when it
/// returns still zoomed. Main only.
@MainActor
final class ImageViewerLoader {
  /// Past this scale over the fit the thumbnail's pixels show, so the full image is worth decoding.
  static let preloadZoom: CGFloat = 1.1

  private let image: ImageViewerView
  private let thumbnails: PageThumbnails
  private let onLoaded: (String) -> Void
  private let onFailed: (ScannerCodeError) -> Void
  private let onZoomChanged: (Bool) -> Void

  private var binding: SessionPageBinding?
  private var boundState: ScanSessionState?
  private var pageId: String?
  /// The binding's current source, and the pin behind the shown image.
  private var source: SessionPageSource?
  private var displayedSource: SessionPageSource?

  // The thumbnail is shared through the cache; the full image is this page's own while zoomed.
  private(set) var thumbnail: CGImage?
  private(set) var fullImage: CGImage?

  // A result is applied only while its generation is current, and a queued decode that is
  // already superseded is skipped. The page generation changes with the source; the full
  // generation also when the full image is dropped.
  private let pageGeneration = LoadGeneration()
  private let fullGeneration = LoadGeneration()
  private var fullPending = false
  /// Whether the current source's thumbnail failed to load.
  private var failed = false
  private(set) var zoomed = false

  init(image: ImageViewerView, thumbnails: PageThumbnails = .shared,
       onLoaded: @escaping (String) -> Void, onFailed: @escaping (ScannerCodeError) -> Void,
       onZoomChanged: @escaping (Bool) -> Void = { _ in }) {
    self.image = image
    self.thumbnails = thumbnails
    self.onLoaded = onLoaded
    self.onFailed = onFailed
    self.onZoomChanged = onZoomChanged
    image.onZoom = { [weak self] scale in
      guard let self else { return }
      if scale >= Self.preloadZoom { loadFull() } else if scale <= 1 { release() }
      if (scale > 1) != zoomed {
        zoomed = scale > 1
        self.onZoomChanged(zoomed)
      }
    }
    image.onHidden = { [weak self] in self?.release() }
    image.onVisible = { [weak self] in
      self?.restore()
      self?.retry()
    }
    image.onAttach = { [weak self] in self?.binding?.start() }
    image.onDetach = { [weak self] in
      guard let self else { return }
      binding?.close()
      source?.close(); source = nil
      drop()
    }
  }

  func bind(_ state: ScanSessionState, _ id: String) {
    if boundState === state && pageId == id {
      if image.window != nil { binding?.start() }
      return
    }
    clear()
    boundState = state
    pageId = id
    binding = SessionPageBinding(state: state, id: id, changed: { [weak self] next in
      guard let self else { next?.close(); return }
      changed(next)
    }, failed: { [weak self] in self?.onFailed($0) })
    if image.window != nil { binding?.start() }
  }

  /// Drops the images, any pending decode and the binding.
  func clear() {
    binding?.close(); binding = nil
    boundState = nil; pageId = nil
    source?.close(); source = nil
    drop()
  }

  /// Loads the page again if its thumbnail failed, reporting a new failure; the shown
  /// image stays meanwhile.
  func retry() {
    guard failed, image.window != nil, source != nil else { return }
    load()
  }

  /// Loads the full image again if the page is zoomed without it, as after `release`.
  func restore() {
    if image.zoomScale >= Self.preloadZoom { loadFull() }
  }

  /// Drops the full image, or its pending decode, and shows the thumbnail.
  func release() {
    fullGeneration.advance()
    fullPending = false
    guard fullImage != nil else { return }
    fullImage = nil
    // The same picture, so the thumbnail replaces it in place.
    if let thumbnail { image.replace(thumbnail) }
  }

  /// The binding delivers only changed sources, so every source is loaded.
  private func changed(_ next: SessionPageSource?) {
    source?.close()
    source = next
    if next != nil, image.window != nil { load() } else { drop() }
  }

  /// Shows the current source's thumbnail; the shown image stays until it is ready, then
  /// fades out under it.
  private func load() {
    let page = pageGeneration.advance()
    release()
    thumbnail = nil
    failed = false
    guard let source else { return image.clearImage() }
    if let cached = thumbnails.cached(source.cacheKey) { return applyThumbnail(cached) }
    let thumbnails = thumbnails
    ScannerImageReads.read(source.retain(), generation: pageGeneration, page, { try thumbnails.load($0) }) { [weak self] result in
      guard let self, pageGeneration.isCurrent(page), binding?.valid != false else { return }
      switch result {
      case .success(let decoded): applyThumbnail(decoded.image)
      case .failure:
        failed = true
        onFailed(ScannerCodeError(code: .imageLoadFailed))
      }
    }
  }

  /// Drops the images and any pending decode, keeping the binding to load once attached.
  private func drop() {
    pageGeneration.advance()
    release()
    thumbnail = nil
    failed = false
    image.clearImage()
    displayedSource?.close(); displayedSource = nil
  }

  private func applyThumbnail(_ next: CGImage) {
    thumbnail = next
    let previous = displayedSource
    displayedSource = source?.retain()
    image.fadeIn(next) { previous?.close() }
    if let pageId { onLoaded(pageId) }
  }

  private func loadFull() {
    // Zooming over the previous page's image waits for this page's thumbnail.
    guard let source, thumbnail != nil, fullImage == nil, !fullPending else { return }
    fullPending = true
    let full = fullGeneration.advance()
    ScannerImageReads.read(source.retain(), generation: fullGeneration, full, {
      try DisplayImageDecoder.decode($0.url, longSide: DisplayImageDecoder.fullLongSide)
    }) { [weak self] result in
      guard let self, fullGeneration.isCurrent(full), binding?.valid != false else { return }
      fullPending = false
      // A failed full decode keeps the thumbnail; zooming again retries it.
      guard case .success(let decoded) = result else { return }
      // The same picture, so it replaces the thumbnail in place, keeping the zoom.
      fullImage = decoded.image
      image.replace(decoded.image)
    }
  }
}
