import type { DocumentPoint, DocumentQuad } from 'react-native-document-scanner'

/** Pulls every corner inside the photo, which is the editing boundary. */
export function clampQuadToFrame(quad: DocumentQuad): DocumentQuad {
  const inside = ({ x, y }: DocumentPoint) => ({ x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) })
  return {
    topLeft: inside(quad.topLeft),
    topRight: inside(quad.topRight),
    bottomRight: inside(quad.bottomRight),
    bottomLeft: inside(quad.bottomLeft),
  }
}

export function sameQuad(a: DocumentQuad, b: DocumentQuad): boolean {
  const same = (p: DocumentPoint, q: DocumentPoint) => p.x === q.x && p.y === q.y
  return same(a.topLeft, b.topLeft) && same(a.topRight, b.topRight) && same(a.bottomRight, b.bottomRight) &&
    same(a.bottomLeft, b.bottomLeft)
}

export function isValidEditableQuad(quad: DocumentQuad): boolean {
  const points = [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft]
  if (points.some(({ x, y }) => !Number.isFinite(x) || !Number.isFinite(y) || x < -0.8 || x > 1.8 || y < -0.8 || y > 1.8)) {
    return false
  }
  const turns = points.map((point, index) => cross(point, points[(index + 1) % 4], points[(index + 2) % 4]))
  if (!turns.every((turn) => turn > 1e-8)) return false
  const area = Math.abs(points.reduce((sum, point, index) => {
    const next = points[(index + 1) % 4]
    return sum + point.x * next.y - next.x * point.y
  }, 0)) / 2
  // Four strictly positive turns already make the outline convex and simple, so no edges can cross.
  return area >= 1e-4 && area <= 4
}

function cross(a: DocumentPoint, b: DocumentPoint, c: DocumentPoint) {
  return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}
