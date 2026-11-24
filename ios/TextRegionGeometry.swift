import CoreGraphics

/// Nitro structs carry no `Equatable`.
func sameTextRegion(_ a: TextRegion, _ b: TextRegion) -> Bool {
  a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height
}

/// `region`, as fractions of the view, clamped to it.
func clampedTextRegion(_ region: TextRegion) -> TextRegion {
  let x = min(max(region.x, 0), 1)
  let y = min(max(region.y, 0), 1)
  return TextRegion(x: x, y: y, width: min(max(region.width, 0), 1 - x), height: min(max(region.height, 0), 1 - y))
}

/// Maps a view region onto an upright buffer the view shows aspect-filled, as normalized buffer
/// coordinates with a top-left origin, clamped to the buffer.
func textRegionInBuffer(_ region: TextRegion, view: CGSize, buffer: CGSize) -> CGRect {
  guard view.width > 0, view.height > 0, buffer.width > 0, buffer.height > 0 else { return .zero }
  let scale = max(view.width / buffer.width, view.height / buffer.height)
  let shown = CGSize(width: buffer.width * scale, height: buffer.height * scale)
  let ox = (shown.width - view.width) / 2
  let oy = (shown.height - view.height) / 2
  let rect = CGRect(x: (region.x * view.width + ox) / shown.width, y: (region.y * view.height + oy) / shown.height,
                    width: region.width * view.width / shown.width, height: region.height * view.height / shown.height)
  return rect.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
}
