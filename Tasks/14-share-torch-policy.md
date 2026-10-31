# 14 � Keep automatic torch policy platform-native

[Back to task index](../TASKS.md)

Status: Android complete; iOS parity pending Task 27

The threshold-and-latch policy stays in Kotlin and Swift. Its small size does not justify a C++/JNI owner. C++ torch code, registration and build wiring have been removed. Hardware commands, capture deferral, request identity and readiness remain platform-owned.

Preserve exposure-ms times ISO >= 20,000, one second of sustained dark observations, reset on bright/invalid/missing ISO, and latch until camera stop or mode change. Use explicit monotonic observation times separately from exposure durations.

Android retains policy boundary/interruption/reset tests and fake-CameraX hardware-controller tests. Shared test vectors live in tests/fixtures/parity/torch.tsv; Android and the future Swift policy must consume them. Task 27 must implement the Swift policy and pass these vectors; Android validation does not establish iOS readiness.

Validation after rollback: Kotlin TorchPolicyTest JVM suite passed (shared vectors and threshold/latch/reset cases); all 7 TorchControllerTest Android cases passed (0.028 s). Native APK rebuilt successfully without the C++ torch owner. Initial sandboxed JVM attempt failed at Gradle dependency resolution; filesystem-escalated retry passed. Focus files are unchanged; no camera/torch activation occurred. Changes are uncommitted.
