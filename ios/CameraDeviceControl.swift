import AVFoundation
import CoreMedia
import Foundation
import ImageIO

/// What the configured device supports, read once its active format is set.
struct CameraCapabilities: Equatable, Sendable {
  /// A focus point of interest can be set.
  var focusPoint = false
  /// `.autoFocus`: one scan that then holds the lens.
  var scanFocus = false
  var continuousFocus = false
  var exposurePoint = false
  var torch = false
  /// Exposure target bias in EV; nil without an adjustable bias.
  var exposureBias: ClosedRange<Double>?
  /// The active format's frame-rate intervals.
  var frameRates: [FrameRateRange] = []

  /// Taps and capture scans can move the lens to a point.
  var focusScans: Bool { focusPoint && scanFocus }
}

/// A hardware request, applied on the camera session's owner queue in submission order.
enum CameraCommand: Equatable, Sendable {
  /// Continuous autofocus and auto-exposure aimed at a device point, in AVFoundation's
  /// sensor-landscape unit coordinates.
  case continuousFocus(CGPoint)
  /// One focus scan at `focus` that then holds the lens; `meter` also moves the exposure point.
  case scanFocus(CGPoint, meter: CGPoint?)
  case torch(Bool)
  /// EV, already clamped to the device's range.
  case exposureBias(Double)
  /// Nil restores the active format's own frame durations.
  case frameRate(FrameRateRange?)
}

/// The device's focus state, sampled on the session owner after it changed. AVFoundation stamps
/// neither the transition nor its frame, so the sample's place among command completions is its
/// only time. `locked` means focus mode `.locked`, which a scan enters when it ends, focused or not.
struct FocusObservation: Equatable, Sendable {
  let adjusting: Bool
  let locked: Bool
}

/// Exposure evidence for one delivered frame. `hostNs` is the frame's presentation time converted
/// from the session's clock to the host clock. Exposure and ISO come from the frame's own EXIF
/// attachment; when it is missing they are the device's current settings, read as the frame is
/// delivered, and `estimated` says so. Unreported values are nil.
struct CameraFrameMetadata: Equatable, Sendable {
  let hostNs: Int64
  let exposureNs: Int64?
  let iso: Double?
  let estimated: Bool
}

/// The host clock in nanoseconds: mach absolute time, as `DispatchTime` uptime reports it.
func hostClockNs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds) }

enum CameraDeviceAdapter {
  static func capabilities(_ device: AVCaptureDevice) -> CameraCapabilities {
    let lower = Double(device.minExposureTargetBias), upper = Double(device.maxExposureTargetBias)
    return CameraCapabilities(
      focusPoint: device.isFocusPointOfInterestSupported,
      scanFocus: device.isFocusModeSupported(.autoFocus),
      continuousFocus: device.isFocusModeSupported(.continuousAutoFocus),
      exposurePoint: device.isExposurePointOfInterestSupported,
      torch: device.hasTorch && device.isTorchModeSupported(.on),
      exposureBias: lower < upper ? lower...upper : nil,
      frameRates: device.activeFormat.videoSupportedFrameRateRanges.map {
        FrameRateRange(min: $0.minFrameRate, max: $0.maxFrameRate)
      })
  }

  /// Applies one command under the device's configuration lock; false when the device refuses or
  /// does not support it. Session owner queue only.
  static func apply(_ command: CameraCommand, to device: AVCaptureDevice) -> Bool {
    do { try device.lockForConfiguration() } catch { return false }
    defer { device.unlockForConfiguration() }
    switch command {
    case .continuousFocus(let point):
      if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = point }
      if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
      meter(device, at: point)
      return true
    case .scanFocus(let point, let meter):
      guard device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.autoFocus) else { return false }
      device.focusPointOfInterest = point
      // Setting the mode starts the scan, even when it is already `.autoFocus`.
      device.focusMode = .autoFocus
      if let meter { self.meter(device, at: meter) }
      return true
    case .torch(let lit):
      guard device.hasTorch, device.isTorchAvailable else { return false }
      if lit {
        do { try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel) } catch { return false }
      } else {
        guard device.isTorchModeSupported(.off) else { return false }
        device.torchMode = .off
      }
      return true
    case .exposureBias(let ev):
      guard Float(ev) >= device.minExposureTargetBias, Float(ev) <= device.maxExposureTargetBias else { return false }
      device.setExposureTargetBias(Float(ev), completionHandler: nil)
      return true
    case .frameRate(let range):
      guard let range else {
        device.activeVideoMinFrameDuration = .invalid
        device.activeVideoMaxFrameDuration = .invalid
        return true
      }
      // Durations outside a supported range raise; clamp to the advertised range's own durations.
      let epsilon = 0.001
      guard let supported = device.activeFormat.videoSupportedFrameRateRanges.first(where: {
        $0.minFrameRate - epsilon <= range.min && range.max <= $0.maxFrameRate + epsilon
      }) else { return false }
      let shortest = clamp(duration(range.max), supported)
      let longest = clamp(duration(range.min), supported)
      guard CMTimeCompare(shortest, longest) <= 0 else { return false }
      device.activeVideoMinFrameDuration = shortest
      device.activeVideoMaxFrameDuration = longest
      return true
    }
  }

  private static func meter(_ device: AVCaptureDevice, at point: CGPoint) {
    guard device.isExposurePointOfInterestSupported, device.isExposureModeSupported(.continuousAutoExposure) else {
      return
    }
    device.exposurePointOfInterest = point
    // The point applies once the mode is set again.
    device.exposureMode = .continuousAutoExposure
  }

  private static func duration(_ fps: Double) -> CMTime {
    CMTimeMakeWithSeconds(1 / fps, preferredTimescale: 1_000_000_000)
  }

  private static func clamp(_ time: CMTime, _ range: AVFrameRateRange) -> CMTime {
    if CMTimeCompare(time, range.minFrameDuration) < 0 { return range.minFrameDuration }
    if CMTimeCompare(time, range.maxFrameDuration) > 0 { return range.maxFrameDuration }
    return time
  }

  static func focusObservation(_ device: AVCaptureDevice) -> FocusObservation {
    FocusObservation(adjusting: device.isAdjustingFocus, locked: device.focusMode == .locked)
  }

  /// A frame's exposure evidence; `device` supplies estimated values when the frame has no EXIF.
  static func metadata(_ sampleBuffer: CMSampleBuffer, hostNs: Int64, device: AVCaptureDevice?) -> CameraFrameMetadata {
    if let exif = CMGetAttachment(sampleBuffer, key: kCGImagePropertyExifDictionary, attachmentModeOut: nil)
         as? [String: Any],
       let seconds = (exif[kCGImagePropertyExifExposureTime as String] as? NSNumber)?.doubleValue,
       seconds.isFinite, seconds > 0 {
      let iso = (exif[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?.doubleValue
      return CameraFrameMetadata(hostNs: hostNs, exposureNs: Int64((seconds * 1e9).rounded()), iso: iso,
                                 estimated: false)
    }
    if let device {
      let duration = device.exposureDuration
      if duration.isNumeric {
        let ns = CMTimeConvertScale(duration, timescale: 1_000_000_000, method: .roundHalfAwayFromZero).value
        return CameraFrameMetadata(hostNs: hostNs, exposureNs: ns, iso: Double(device.iso), estimated: true)
      }
    }
    return CameraFrameMetadata(hostNs: hostNs, exposureNs: nil, iso: nil, estimated: false)
  }
}
