import Foundation
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// Presents the system photo picker, which needs no library permission, and stages each pick in picked order. A
/// pick's file exists only inside its provider callback, so it is cloned into a temporary file whose ownership passes
/// to the caller with the result. One picker is open at a time across sessions. Each picker owns its slot and
/// dismissal and reports at most once, after it is gone. Main thread.
@MainActor
final class LibraryPicker: NSObject, PHPickerViewControllerDelegate, UIAdaptivePresentationControllerDelegate,
                           PickerRequest {
  private static var current: LibraryPicker?
  private let completion: (Result<[Result<URL, Error>], Error>) -> Void
  private weak var controller: PHPickerViewController?
  private var settled = false
  /// Completes once this picker no longer holds the one-picker slot; staged picks can still arrive after it.
  nonisolated let pickerReleased = NativeCompletion()

  private init(completion: @escaping (Result<[Result<URL, Error>], Error>) -> Void) { self.completion = completion }

  /// Presents the picker; `completion` runs at most once, on main. The returned picker's `end()` ends the request
  /// early. Throws when it cannot present, and then holds no slot and never reports.
  static func pick(maxCount: Int, completion: @escaping (Result<[Result<URL, Error>], Error>) -> Void) throws -> LibraryPicker {
    guard current == nil else { throw ScannerCodeError(code: .captureBusy) }
    guard let presenter = topPresenter() else { throw ScannerCodeError(code: .processorUnavailable) }
    // Another controller still dismissing from the presenter would make UIKit drop this presentation silently.
    guard presenter.presentedViewController == nil else { throw ScannerCodeError(code: .captureBusy) }
    var configuration = PHPickerConfiguration()
    configuration.filter = .images
    configuration.selectionLimit = maxCount
    configuration.selection = .ordered
    configuration.preferredAssetRepresentationMode = .current
    let picker = PHPickerViewController(configuration: configuration)
    let owner = LibraryPicker(completion: completion)
    owner.controller = picker
    picker.delegate = owner
    picker.presentationController?.delegate = owner
    current = owner
    presenter.present(picker, animated: true)
    return owner
  }

  /// Ends a picker that is still open: it is dismissed and reports `processor/disposed`. Picks already being staged
  /// are still delivered, so their owner can delete them. `pickerReleased` completes once the one-picker slot is free,
  /// after any dismissal, whatever late provider callbacks remain. Main thread.
  nonisolated func end() { MainActor.assumeIsolated { endOnMain() } }

  private func endOnMain() {
    guard LibraryPicker.current === self else { return }
    if let controller {
      finish(controller, .failure(ScannerCodeError(code: .processorDisposed)))
    } else {
      // The controller went away without any dismissal callback, so nothing else would free the slot.
      releaseSlot()
      if !settled {
        settled = true
        completion(.failure(ScannerCodeError(code: .processorDisposed)))
      }
    }
  }

  private func releaseSlot() {
    if LibraryPicker.current === self { LibraryPicker.current = nil }
    pickerReleased.complete()
  }

  nonisolated func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
    // PHPicker calls its delegate on main; the providers are only handed to their own loading callbacks.
    nonisolated(unsafe) let providers = results.map(\.itemProvider)
    MainActor.assumeIsolated { finish(picker, .success(providers)) }
  }

  /// A swipe can close the sheet without a picking callback; it settles the call as a cancellation.
  func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
    finish(presentationController.presentedViewController, .success([]))
  }

  /// Settles once, from whichever of picking, a swipe or `end()` comes first.
  private func finish(_ picker: UIViewController, _ outcome: Result<[NSItemProvider], Error>) {
    guard !settled else { return }
    settled = true
    // The slot is released, and the result delivered, only once the picker is gone: a caller retrying on the
    // result could otherwise present during the dismissal, which UIKit drops without any delegate callback.
    let dismissal = DispatchGroup()
    dismissal.enter()
    let dismissed = { [self] in
      releaseSlot()
      dismissal.leave()
    }
    // A swiped-away picker is already gone and has nothing left to animate.
    if picker.presentingViewController == nil { dismissed() } else { picker.dismiss(animated: true, completion: dismissed) }
    switch outcome {
    case .success(let providers): Self.stage(providers, after: dismissal, completion: completion)
    case .failure(let error):
      nonisolated(unsafe) let completion = completion
      dismissal.notify(queue: .main) { completion(.failure(error)) }
    }
  }

  /// Stages providers concurrently and reports their files in picked order, once `group`'s earlier work (the dismissal)
  /// has also finished. A pick the provider cannot read fails as `image/load-failed`; failing to create the staging
  /// directory or write the staged copy fails as `storage/write-failed`.
  private static func stage(_ providers: [NSItemProvider], after group: DispatchGroup,
                            completion: @escaping (Result<[Result<URL, Error>], Error>) -> Void) {
    let staged = StagedPicks(count: providers.count)
    for (index, provider) in providers.enumerated() {
      group.enter()
      provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
        defer { group.leave() }
        guard let url else { return }
        staged.set(index, stage(url, into: FileManager.default.temporaryDirectory.appendingPathComponent("DocumentScannerImports")))
      }
    }
    nonisolated(unsafe) let completion = completion
    group.notify(queue: .main) { completion(.success(staged.snapshot)) }
  }

  /// Copies a provider file, which exists only inside its callback, into `directory`. Reading the provider file fails
  /// as `image/load-failed`, so the pick is skipped; creating the directory or writing the copy as `storage/write-failed`.
  nonisolated static func stage(_ source: URL, into directory: URL) -> Result<URL, Error> {
    let data: Data
    do { data = try Data(contentsOf: source, options: .mappedIfSafe) }
    catch { return .failure(ScannerCodeError(code: .imageLoadFailed)) }
    let copy = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension(source.pathExtension)
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      try data.write(to: copy)
      return .success(copy)
    } catch {
      try? FileManager.default.removeItem(at: copy)
      return .failure(ScannerCodeError(code: .storageWriteFailed))
    }
  }

  private static func topPresenter() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let window = scenes.filter { $0.activationState == .foregroundActive }.flatMap(\.windows).first(where: \.isKeyWindow)
      ?? scenes.flatMap(\.windows).first(where: \.isKeyWindow)
    var top = window?.rootViewController
    while let presented = top?.presentedViewController, !presented.isBeingDismissed { top = presented }
    return top
  }
}

/// Picked files by slot, unreadable until staged. Provider callbacks run concurrently on their own queues; the lock
/// owns the array.
final class StagedPicks: @unchecked Sendable {
  private let lock = NSLock()
  private var urls: [Result<URL, Error>]

  init(count: Int) { urls = Array(repeating: .failure(ScannerCodeError(code: .imageLoadFailed)), count: count) }

  func set(_ index: Int, _ pick: Result<URL, Error>) {
    lock.lock(); defer { lock.unlock() }
    urls[index] = pick
  }

  var snapshot: [Result<URL, Error>] {
    lock.lock(); defer { lock.unlock() }
    return urls
  }
}
