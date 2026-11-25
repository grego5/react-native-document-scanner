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

