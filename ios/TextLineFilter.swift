import Foundation

/// One recognized word: its text and horizontal extent in an upright, top-left-origin crop.
struct RecognizedWord: Equatable {
  var text: String
  var left: Double
  var right: Double
}

/// One recognized line in an upright, top-left-origin crop: its words left to right, its vertical
/// extent, and `confidence`, 0 when unknown.
struct RecognizedLine: Equatable {
  var words: [RecognizedWord]
  var top: Double
  var bottom: Double
  var confidence: Double
  var text: String { words.map { $0.text.trimmingCharacters(in: .whitespaces) }.joined(separator: " ") }
  var left: Double { words.map(\.left).min() ?? 0 }
  var right: Double { words.map(\.right).max() ?? 0 }
  var centerY: Double { (top + bottom) / 2 }
}

/// Turns raw recognized lines into one accepted reading. Lines sharing a baseline join left to
/// right. Without allowed characters each joined line is a candidate. With them, every character
/// that is neither allowed nor swappable for an allowed look-alike, whitespace included unless a
/// space is allowed, splits the line, so surrounding words never join the reading. The accepted
/// candidate nearest the crop's centre wins. Same rules as Kotlin's `TextLineFilter`.
struct TextLineFilter {
  /// A filtered piece of a line and its extent in the crop.
  struct Candidate: Equatable {
    var text: String
    var left: Double
    var top: Double
    var right: Double
    var bottom: Double
    var confidence: Double
    var centerX: Double { (left + right) / 2 }
    var centerY: Double { (top + bottom) / 2 }
  }

  private let allowed: Set<Character>?
  private let minLength: Int
  private let regex: NSRegularExpression?
  let patternInvalid: Bool

  init(allowedCharacters: String, pattern: String, minLength: Int) {
    allowed = allowedCharacters.isEmpty ? nil : Set(allowedCharacters)
    self.minLength = max(1, minLength)
    regex = pattern.isEmpty ? nil : try? NSRegularExpression(pattern: pattern)
    patternInvalid = !pattern.isEmpty && regex == nil
  }

  func select(_ lines: [RecognizedLine], cropWidth: Double, cropHeight: Double) -> Candidate? {
    guard !patternInvalid, cropWidth > 0, cropHeight > 0 else { return nil }
    func distance(_ candidate: Candidate) -> Double {
      let dx = (candidate.centerX - cropWidth / 2) / cropWidth
      let dy = (candidate.centerY - cropHeight / 2) / cropHeight
      return dx * dx + dy * dy
    }
    var best: Candidate?
    for candidate in Self.baselines(lines).flatMap(candidates) where accepted(candidate) {
      if best == nil || distance(candidate) < distance(best!) { best = candidate }
    }
    return best
  }

  /// The filtered pieces of `line`: the whole line without a character set, otherwise its allowed runs.
  func candidates(_ line: RecognizedLine) -> [Candidate] {
    guard let allowed else {
      let text = line.text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
      return [Candidate(text: text, left: line.left, top: line.top, right: line.right, bottom: line.bottom,
                        confidence: line.confidence)]
    }
    var out: [Candidate] = []
    var text: [Character] = []
    // Each kept character's horizontal extent.
    var lefts: [Double] = []
    var rights: [Double] = []
    func flush() {
      // A run may start or end with an allowed space; its extent spans only the kept characters.
      if let first = text.firstIndex(where: { $0 != " " }), let last = text.lastIndex(where: { $0 != " " }) {
        out.append(Candidate(text: String(text[first...last]), left: lefts[first], top: line.top, right: rights[last],
                             bottom: line.bottom, confidence: line.confidence))
      }
      text.removeAll()
      lefts.removeAll()
      rights.removeAll()
    }
    func put(_ c: Character, _ left: Double, _ right: Double) {
      guard let kept = allowed.contains(c) ? c : substitute(c, allowed) else { return flush() }
      text.append(kept)
      lefts.append(left)
      rights.append(right)
    }
    let words = line.words.filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }.sorted { $0.left < $1.left }
    for (index, word) in words.enumerated() {
      if index > 0 { put(" ", words[index - 1].right, word.left) }
      let chars = Array(word.text.trimmingCharacters(in: .whitespaces))
      let width = (word.right - word.left) / Double(chars.count)
      for (i, c) in chars.enumerated() { put(c, word.left + Double(i) * width, word.left + Double(i + 1) * width) }
    }
    flush()
    return out
  }

  /// Whether `raw`'s filtered text, as one piece, is long enough and matches the pattern.
  func accept(_ raw: String) -> String? {
    let pieces = candidates(RecognizedLine(words: [RecognizedWord(text: raw, left: 0, right: Double(raw.count))],
                                           top: 0, bottom: 1, confidence: 0))
    guard pieces.count == 1, accepted(pieces[0]) else { return nil }
    return pieces[0].text
  }

  private func accepted(_ candidate: Candidate) -> Bool {
    let text = candidate.text
    guard text.count >= minLength else { return false }
    if let regex {
      let range = NSRange(text.startIndex..., in: text)
      guard let match = regex.firstMatch(in: text, options: [.anchored], range: range), match.range == range else { return false }
    }
    return true
  }

  private func substitute(_ c: Character, _ allowed: Set<Character>) -> Character? {
    if c.isWhitespace { return allowed.contains(" ") ? " " : nil }
    let upper = Character(c.uppercased().count == 1 ? c.uppercased() : String(c))
    let lower = Character(c.lowercased().count == 1 ? c.lowercased() : String(c))
    if allowed.contains(upper) { return upper }
    if allowed.contains(lower) { return lower }
    return ((Self.lookalikes[c] ?? "") + (Self.lookalikes[upper] ?? "")).first { allowed.contains($0) }
  }

  private static let lookalikes: [Character: String] = [
    "0": "OQD", "O": "0Q", "Q": "O0", "D": "0O",
    "1": "IL|", "I": "1L|", "L": "1I", "|": "1IL",
    "2": "Z", "Z": "2", "5": "S", "S": "5",
    "6": "G", "G": "6", "8": "B", "B": "8",
  ]

  /// Joins lines whose vertical centres fall within each other's height, their words left to right.
  static func baselines(_ lines: [RecognizedLine]) -> [RecognizedLine] {
    var groups: [[RecognizedLine]] = []
    let sorted = lines.filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }
      .enumerated().sorted { ($0.element.centerY, $0.offset) < ($1.element.centerY, $1.offset) }.map(\.element)
    for line in sorted {
      if let last = groups.last?.last,
         (last.top...last.bottom).contains(line.centerY), (line.top...line.bottom).contains(last.centerY) {
        groups[groups.count - 1].append(line)
      } else {
        groups.append([line])
      }
    }
    return groups.map { group in
      RecognizedLine(
        words: group.flatMap(\.words).enumerated().sorted { ($0.element.left, $0.offset) < ($1.element.left, $1.offset) }.map(\.element),
        top: group.map(\.top).min()!, bottom: group.map(\.bottom).max()!,
        confidence: group.map(\.confidence).reduce(0, +) / Double(group.count))
    }
  }
}

/// Agreement over recent frames: the text read most often within `maxAgeMs` and the last `window`
/// frames wins, the newest breaking ties. Frames without an accepted reading count toward the
/// window, so a region that stops showing text clears within it.
struct TextVotes {
  struct Winner: Equatable {
    var text: String
    var confidence: Double
    var votes: Int
  }
  private struct Vote {
    var text: String?
    var confidence: Double
    var atMs: Int64
  }
  private let window: Int
  private let maxAgeMs: Int64
  private var votes: [Vote] = []

  init(window: Int = 8, maxAgeMs: Int64 = 1_500) {
    self.window = window
    self.maxAgeMs = maxAgeMs
  }

  mutating func add(_ text: String?, confidence: Double, nowMs: Int64) -> Winner? {
    votes.append(Vote(text: text, confidence: confidence, atMs: nowMs))
    if votes.count > window { votes.removeFirst(votes.count - window) }
    votes.removeAll { nowMs - $0.atMs > maxAgeMs }
    return winner()
  }

  mutating func clear() { votes.removeAll() }

  func winner() -> Winner? {
    var best: Winner?
    var counted = Set<String>()
    for vote in votes.reversed() {
      guard let text = vote.text, counted.insert(text).inserted else { continue }
      let agreeing = votes.filter { $0.text == text }
      if best == nil || agreeing.count > best!.votes {
        best = Winner(text: text, confidence: agreeing.map(\.confidence).reduce(0, +) / Double(agreeing.count),
                      votes: agreeing.count)
      }
    }
    return best
  }
}

/// Nitro structs carry no `Equatable`.
func sameTextRegion(_ a: TextRegion, _ b: TextRegion) -> Bool {
  a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height
}

/// `candidate`'s extent in an upright crop of `cropWidth` by `cropHeight` showing `region`, as fractions of the view.
func textBoundsInView(_ candidate: TextLineFilter.Candidate, region: TextRegion, cropWidth: Double, cropHeight: Double) -> TextRegion {
  let left = min(max(candidate.left / cropWidth, 0), 1)
  let top = min(max(candidate.top / cropHeight, 0), 1)
  let right = min(max(candidate.right / cropWidth, left), 1)
  let bottom = min(max(candidate.bottom / cropHeight, top), 1)
  return TextRegion(x: region.x + left * region.width, y: region.y + top * region.height,
                    width: (right - left) * region.width, height: (bottom - top) * region.height)
}

/// Nitro optionals of structs carry no `Equatable`.
func sameTextBounds(_ a: TextRegion?, _ b: TextRegion?) -> Bool {
  switch (a, b) {
  case (nil, nil): return true
  case let (a?, b?): return sameTextRegion(a, b)
  default: return false
  }
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
