import UIKit

/// Pages of `ImageViewerView` side by side in a recycling collection view, one view width
/// each, each loaded by its own `ImageViewerLoader`. The pager alone arbitrates gestures:
/// paging is off while the shown page is zoomed, so its drags pan it, and on at the fit
/// when swipes are enabled. A page that leaves, by a swipe or a jump, fits again. One page
/// beyond each side is laid out, so a neighbour has its thumbnail before a swipe reveals
/// it. Only the shown page reports a failure; a neighbour that failed loads again once
/// shown. Main only.
@MainActor
final class ImagePagerView: UIView, UICollectionViewDataSource, UICollectionViewDelegate {
  private static let reuseIdentifier = "page"

  var swipeEnabled = true { didSet { updateScrolling() } }
  private(set) var current = 0
  /// The last index asked for; only a new one moves the pager, so other prop updates
  /// never interrupt a swipe.
  private var requested = -1
  private var session: ScanSessionState?
  private(set) var pageIds: [String] = []
  private var direction = ImagePagerDirection.auto
  private var laidOutSize = CGSize.zero

  private let thumbnails: PageThumbnails
  private let onPageSettled: (Int) -> Void
  private let onImageLoaded: (String) -> Void
  private let onImageFailed: (ScannerCodeError) -> Void
  private let layout = PageLayout()
  let collectionView: UICollectionView
  /// Every cell the collection view made; it reuses them, so they stay few.
  private(set) var cells: [ImagePagerCell] = []

  init(thumbnails: PageThumbnails = .shared, onPageSettled: @escaping (Int) -> Void,
       onImageLoaded: @escaping (String) -> Void, onImageFailed: @escaping (ScannerCodeError) -> Void) {
    self.thumbnails = thumbnails
    self.onPageSettled = onPageSettled
    self.onImageLoaded = onImageLoaded
    self.onImageFailed = onImageFailed
    collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
    super.init(frame: .zero)
    // The collection view spans one page beyond each side, so UIKit lays out and loads
    // the neighbours; the pager clips them. Being wider than a page, it snaps by itself.
    clipsToBounds = true
    collectionView.decelerationRate = .fast
    collectionView.bounces = false
    collectionView.alwaysBounceVertical = false
    collectionView.showsHorizontalScrollIndicator = false
    collectionView.showsVerticalScrollIndicator = false
    collectionView.contentInsetAdjustmentBehavior = .never
    collectionView.backgroundColor = .clear
    // Prefetched cells would load beyond the neighbours.
    collectionView.isPrefetchingEnabled = false
    collectionView.register(ImagePagerCell.self, forCellWithReuseIdentifier: Self.reuseIdentifier)
    collectionView.dataSource = self
    collectionView.delegate = self
    addSubview(collectionView)
    registerForTraitChanges([UITraitLayoutDirection.self]) { (self: ImagePagerView, _: UITraitCollection) in
      guard self.direction == .auto else { return }
      self.updateMirroring()
      self.scroll(to: self.current)
    }
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

  /// Retains the selection by ID across order changes; a replaced session releases every
  /// old binding.
  func setPages(_ next: [String], state: ScanSessionState?) {
    if next == pageIds && state === session { return }
    let selected = state === session && pageIds.indices.contains(current) ? pageIds[current] : nil
    for cell in cells where state !== session || !next.contains(cell.boundId ?? "") { release(cell) }
    session = state
    pageIds = next
    collectionView.reloadData()
    collectionView.layoutIfNeeded()
    if let selected, let retained = next.firstIndex(of: selected) {
      settle(retained)
    } else {
      // The host chooses the next selection; meanwhile the pager stays where it is.
      requested = -1
      current = min(current, max(next.count - 1, 0))
    }
    scroll(to: current)
  }

  func setDirection(_ next: ImagePagerDirection) {
    guard next != direction else { return }
    direction = next
    collectionView.semanticContentAttribute = switch next {
    case .auto: .unspecified
    case .ltr: .forceLeftToRight
    case .rtl: .forceRightToLeft
    }
    updateMirroring()
    layout.invalidateLayout()
    scroll(to: current)
  }

  /// `auto` follows the app's layout direction.
  private func updateMirroring() {
    layout.mirrored = direction == .rtl || (direction == .auto && effectiveUserInterfaceLayoutDirection == .rightToLeft)
  }

  /// Jumps to `index`, clamped to the pages, when it differs from the index asked for before.
  func show(_ index: Int) {
    let target = min(max(index, 0), max(pageIds.count - 1, 0))
    guard target != requested else { return }
    requested = target
    if target == current && !collectionView.visibleCells.isEmpty { return }
    settle(target)
    scroll(to: target)
  }

  /// Drops every binding and page once the host has dropped the pager.
  func release() {
    for cell in cells { release(cell) }
    session = nil
    pageIds = []
    current = 0
    requested = -1
    collectionView.reloadData()
  }

  /// A user scroll came to rest; reports the page it settled on if that is another one.
  func scrollSettled() {
    let center = CGPoint(x: collectionView.bounds.midX, y: collectionView.bounds.midY)
    guard let index = collectionView.indexPathForItem(at: center)?.item, index != current else { return }
    settle(index)
    onPageSettled(index)
  }

  /// The shown page's cell, if laid out.
  var shownCell: ImagePagerCell? { cell(at: current) }

  func cell(at index: Int) -> ImagePagerCell? {
    collectionView.cellForItem(at: IndexPath(item: index, section: 0)) as? ImagePagerCell
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    guard bounds.size != laidOutSize else { return }
    laidOutSize = bounds.size
    let width = bounds.width
    collectionView.frame = bounds.insetBy(dx: -width, dy: 0)
    // The first and last pages can still come to rest in the pager.
    collectionView.contentInset = UIEdgeInsets(top: 0, left: width, bottom: 0, right: width)
    if width > 0 && bounds.height > 0 { layout.pageSize = bounds.size }
    updateMirroring()
    layout.invalidateLayout()
    scroll(to: current)
  }

  /// A swipe rests on the next page in its direction, one page at a time.
  func scrollViewWillEndDragging(_ scrollView: UIScrollView, withVelocity velocity: CGPoint,
                                 targetContentOffset: UnsafeMutablePointer<CGPoint>) {
    let width = layout.pageSize.width
    guard width > 0, !pageIds.isEmpty else { return }
    let inset = scrollView.contentInset.left
    let position = (scrollView.contentOffset.x + inset) / width
    let slot = velocity.x > 0.2 ? floor(position) + 1 : velocity.x < -0.2 ? ceil(position) - 1 : position.rounded()
    let clamped = min(max(slot, 0), CGFloat(pageIds.count - 1))
    targetContentOffset.pointee = CGPoint(x: clamped * width - inset, y: 0)
  }

  /// Makes `index` the shown page: the others fit again, and it loads again if it had
  /// failed off screen.
  private func settle(_ index: Int) {
    current = index
    for cell in cells where cell.window != nil {
      if collectionView.indexPath(for: cell)?.item == index { cell.loader?.retry() } else { cell.image.resetZoom() }
    }
    updateScrolling()
  }

  private func scroll(to index: Int) {
    guard pageIds.indices.contains(index), layout.pageSize == bounds.size else { return }
    collectionView.layoutIfNeeded()
    guard let frame = collectionView.layoutAttributesForItem(at: IndexPath(item: index, section: 0))?.frame else { return }
    collectionView.setContentOffset(CGPoint(x: frame.minX - collectionView.contentInset.left, y: 0), animated: false)
    collectionView.layoutIfNeeded()
    updateScrolling()
  }

  /// The one owner of gesture arbitration: a zoomed page pans, a fitted pager swipes.
  private func updateScrolling() {
    collectionView.isScrollEnabled = swipeEnabled && shownCell?.image.isZoomed != true
  }

  private func release(_ cell: ImagePagerCell) {
    cell.loader?.clear()
    cell.boundId = nil
  }

  // MARK: UICollectionViewDataSource

  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { pageIds.count }

  func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    let cell = collectionView.dequeueReusableCell(withReuseIdentifier: Self.reuseIdentifier, for: indexPath) as! ImagePagerCell
    if cell.loader == nil {
      cells.append(cell)
      cell.loader = ImageViewerLoader(image: cell.image, thumbnails: thumbnails,
        onLoaded: { [weak self] in self?.onImageLoaded($0) },
        onFailed: { [weak self, weak cell] error in
          guard let self, let cell, self.collectionView.indexPath(for: cell)?.item == current else { return }
          onImageFailed(error)
        },
        onZoomChanged: { [weak self] _ in self?.updateScrolling() })
    }
    let id = pageIds[indexPath.item]
    if indexPath.item != current { cell.image.resetZoom() }
    // Binding the same page again keeps its pixels.
    if let session { cell.loader?.bind(session, id) } else { cell.loader?.clear() }
    cell.boundId = id
    return cell
  }

  // MARK: UICollectionViewDelegate

  func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell,
                      forItemAt indexPath: IndexPath) {
    // A cell a reload handed to another item stays bound to that one.
    guard let cell = cell as? ImagePagerCell, collectionView.indexPath(for: cell) == nil else { return }
    release(cell)
  }

  func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { scrollSettled() }

  func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
    if !decelerate { scrollSettled() }
  }
}

/// One pager page: a viewer and the loader bound to its page.
@MainActor
final class ImagePagerCell: UICollectionViewCell {
  let image = ImageViewerView()
  fileprivate(set) var loader: ImageViewerLoader?
  /// The page whose callbacks and leases belong to this cell.
  fileprivate(set) var boundId: String?

  override init(frame: CGRect) {
    super.init(frame: frame)
    image.frame = contentView.bounds
    image.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    contentView.addSubview(image)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}

/// Full-size pages in a row, one per slot; right to left mirrors which page fills a slot.
/// Computed directly, so the order never depends on UIKit's own flipping.
private final class PageLayout: UICollectionViewLayout {
  var pageSize = CGSize.zero {
    didSet { if pageSize != oldValue { invalidateLayout() } }
  }
  var mirrored = false {
    didSet { if mirrored != oldValue { invalidateLayout() } }
  }
  private var count = 0

  override func prepare() {
    super.prepare()
    count = collectionView.map { $0.numberOfSections > 0 ? $0.numberOfItems(inSection: 0) : 0 } ?? 0
  }

  override var collectionViewContentSize: CGSize {
    CGSize(width: pageSize.width * CGFloat(count), height: pageSize.height)
  }

  override func layoutAttributesForElements(in rect: CGRect) -> [UICollectionViewLayoutAttributes]? {
    guard pageSize.width > 0, count > 0 else { return [] }
    let first = max(Int(floor(rect.minX / pageSize.width)), 0)
    let last = min(Int(ceil(rect.maxX / pageSize.width)) - 1, count - 1)
    guard first <= last else { return [] }
    return (first...last).map { attributes(page(inSlot: $0)) }
  }

  override func layoutAttributesForItem(at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
    (0..<count).contains(indexPath.item) ? attributes(indexPath.item) : nil
  }

  override func shouldInvalidateLayout(forBoundsChange newBounds: CGRect) -> Bool { false }

  /// Slots and pages map the same way in both directions.
  private func page(inSlot slot: Int) -> Int { mirrored ? count - 1 - slot : slot }

  private func attributes(_ index: Int) -> UICollectionViewLayoutAttributes {
    let attributes = UICollectionViewLayoutAttributes(forCellWith: IndexPath(item: index, section: 0))
    attributes.frame = CGRect(origin: CGPoint(x: CGFloat(page(inSlot: index)) * pageSize.width, y: 0), size: pageSize)
    return attributes
  }
}
