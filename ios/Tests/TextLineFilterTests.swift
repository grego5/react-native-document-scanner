import XCTest
@testable import ReactNativeDocumentScanner

/// Hardware-free text reading rules: the same cases as Kotlin's `TextLineReadingTest`.
final class TextLineFilterTests: XCTestCase {
  /// A line of `words` spaced 10 px per character with a 10 px gap, starting at `left`.
  private func line(_ top: Double, _ bottom: Double, _ words: String..., left: Double = 0) -> RecognizedLine {
    var x = left
    let placed = words.map { word -> RecognizedWord in
      defer { x += Double(word.count) * 10 + 10 }
      return RecognizedWord(text: word, left: x, right: x + Double(word.count) * 10)
    }
    return RecognizedLine(words: placed, top: top, bottom: bottom, confidence: 0.9)
  }

  private let serial = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-"

  func testUnrestrictedFilterKeepsTheWholeLine() {
    XCTAssertEqual(TextLineFilter(allowedCharacters: "", pattern: "", minLength: 1).accept("  SN   12\tAB "), "SN 12 AB")
  }

  func testAllowedCharactersSwapLookalikes() {
    XCTAssertEqual(TextLineFilter(allowedCharacters: serial, pattern: "", minLength: 1).accept("ab-l234"), "AB-L234")
    XCTAssertEqual(TextLineFilter(allowedCharacters: "0123456789", pattern: "", minLength: 1).accept("IOS8Z6"), "105826")
  }

  func testRemovedCharactersSplitPieces() {
    let filter = TextLineFilter(allowedCharacters: serial, pattern: "", minLength: 1)
    XCTAssertEqual(filter.candidates(line(0, 10, "SN:", "AB12", "x")).map(\.text), ["SN", "AB12", "X"])
    let digits = TextLineFilter(allowedCharacters: "0123456789", pattern: "", minLength: 1)
    XCTAssertEqual(digits.candidates(line(0, 10, "1#2")).map(\.text), ["1", "2"])
    // An allowed space keeps groups together but is trimmed from the ends.
    let spaced = TextLineFilter(allowedCharacters: serial + " ", pattern: "", minLength: 1)
    XCTAssertEqual(spaced.candidates(line(0, 10, "AB", "12:")).map(\.text), ["AB 12"])
  }

  func testPieceCentresFollowTheirCharacters() {
    let pieces = TextLineFilter(allowedCharacters: serial, pattern: "", minLength: 1).candidates(line(0, 10, "SN:", "AB12"))
    XCTAssertEqual(pieces[0].centerX, 10, accuracy: 1e-9)
    XCTAssertEqual(pieces[1].centerX, 60, accuracy: 1e-9)
  }

  func testPieceExtentsSpanOnlyTheirCharacters() {
    // "SN:" covers 0–30, so "SN" ends at 20; "AB12" covers 40–80 on a line 5–15 high.
    let pieces = TextLineFilter(allowedCharacters: serial, pattern: "", minLength: 1).candidates(line(5, 15, "SN:", "AB12"))
    XCTAssertEqual([pieces[0].left, pieces[0].right, pieces[1].left, pieces[1].right], [0, 20, 40, 80])
    XCTAssertEqual(pieces[1].top, 5)
    XCTAssertEqual(pieces[1].bottom, 15)
  }

  func testBoundsMapFromTheCropIntoTheView() {
    let piece = TextLineFilter.Candidate(text: "AB12", left: 0.25, top: 0.25, right: 0.75, bottom: 0.75, confidence: 0.9)
    let bounds = textBoundsInView(piece, region: TextRegion(x: 0.1, y: 0.4, width: 0.8, height: 0.2), cropWidth: 1, cropHeight: 1)
    XCTAssertEqual(bounds.x, 0.3, accuracy: 1e-9)
    XCTAssertEqual(bounds.y, 0.45, accuracy: 1e-9)
    XCTAssertEqual(bounds.width, 0.4, accuracy: 1e-9)
    XCTAssertEqual(bounds.height, 0.1, accuracy: 1e-9)
  }

  func testPatternAndLengthGateAcceptance() {
    let filter = TextLineFilter(allowedCharacters: "", pattern: "[A-Z]{2}\\d{4}", minLength: 6)
    XCTAssertEqual(filter.accept("AB1234"), "AB1234")
    XCTAssertNil(filter.accept("AB123"))
    XCTAssertNil(filter.accept("ABC234"))
    XCTAssertNil(TextLineFilter(allowedCharacters: "", pattern: "", minLength: 5).accept("ABCD"))
  }

  func testInvalidPatternAcceptsNothing() {
    let filter = TextLineFilter(allowedCharacters: "", pattern: "([", minLength: 1)
    XCTAssertTrue(filter.patternInvalid)
    XCTAssertNil(filter.select([line(40, 60, "ABC")], cropWidth: 100, cropHeight: 100))
  }

  func testSerialModeReadsTheCentrePieceNotItsNeighbours() {
    let filter = TextLineFilter(allowedCharacters: serial, pattern: "", minLength: 4)
    let lines = [line(40, 60, "MODEL", "X200", "SN:", "K7Q-4410", "MADE", "IN")]
    let centre = filter.candidates(lines[0]).first { $0.text == "K7Q-4410" }!.centerX
    XCTAssertEqual(filter.select(lines, cropWidth: centre * 2, cropHeight: 100)?.text, "K7Q-4410")
  }

  func testUnrestrictedSelectionJoinsABaselineAndPrefersTheCentre() {
    let filter = TextLineFilter(allowedCharacters: "", pattern: "", minLength: 1)
    let lines = [line(0, 20, "TOP"), line(42, 58, "4567", left: 60), line(40, 60, "SN-123")]
    XCTAssertEqual(filter.select(lines, cropWidth: 100, cropHeight: 100)?.text, "SN-123 4567")
  }

  func testSelectionSkipsRejectedPieces() {
    let filter = TextLineFilter(allowedCharacters: "", pattern: "\\d+", minLength: 1)
    let lines = [line(40, 60, "LABEL"), line(70, 90, "9876")]
    XCTAssertEqual(filter.select(lines, cropWidth: 100, cropHeight: 100)?.text, "9876")
  }

  func testVotesPickTheMajorityAndNewestOnTies() {
    var votes = TextVotes(window: 5, maxAgeMs: 1_000)
    _ = votes.add("AB12", confidence: 0.8, nowMs: 0)
    _ = votes.add("A812", confidence: 0.6, nowMs: 10)
    let winner = votes.add("AB12", confidence: 1.0, nowMs: 20)
    XCTAssertEqual(winner?.text, "AB12")
    XCTAssertEqual(winner?.votes, 2)
    XCTAssertEqual(winner?.confidence ?? 0, 0.9, accuracy: 1e-6)
    votes.clear()
    _ = votes.add("ONE", confidence: 0.5, nowMs: 0)
    XCTAssertEqual(votes.add("TWO", confidence: 0.5, nowMs: 10)?.text, "TWO")
  }

  func testVotesExpireByWindowAndAge() {
    var votes = TextVotes(window: 3, maxAgeMs: 1_000)
    _ = votes.add("AB12", confidence: 0.9, nowMs: 0)
    _ = votes.add(nil, confidence: 0, nowMs: 10)
    _ = votes.add(nil, confidence: 0, nowMs: 20)
    XCTAssertNil(votes.add(nil, confidence: 0, nowMs: 30))
    _ = votes.add("CD34", confidence: 0.9, nowMs: 40)
    XCTAssertNil(votes.add(nil, confidence: 0, nowMs: 2_000))
  }

  func testRegionMapsThroughAspectFill() {
    let region = TextRegion(x: 0.1, y: 0.4, width: 0.8, height: 0.2)
    // A 9:16 view over a 9:16 buffer maps one to one.
    let same = textRegionInBuffer(region, view: CGSize(width: 90, height: 160), buffer: CGSize(width: 720, height: 1280))
    XCTAssertEqual(same.minX, 0.1, accuracy: 1e-9)
    XCTAssertEqual(same.minY, 0.4, accuracy: 1e-9)
    XCTAssertEqual(same.width, 0.8, accuracy: 1e-9)
    // A square view over a 9:16 buffer shows its vertical middle.
    let square = textRegionInBuffer(region, view: CGSize(width: 90, height: 90), buffer: CGSize(width: 90, height: 160))
    XCTAssertEqual(square.minX, 0.1, accuracy: 1e-9)
    XCTAssertEqual(square.minY, (36 + 35) / 160.0, accuracy: 1e-9)
    XCTAssertEqual(square.height, 18 / 160.0, accuracy: 1e-9)
  }

  func testRegionClampsToTheView() {
    let region = clampedTextRegion(TextRegion(x: 0.5, y: -0.2, width: 0.9, height: 1.5))
    XCTAssertTrue(sameTextRegion(region, TextRegion(x: 0.5, y: 0, width: 0.5, height: 1)))
  }
}
