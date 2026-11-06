# DocCornerNet model tools

Detection uses `resources/doccornernet/model.tflite`. Android packages this shared directory at the unchanged APK path `doccornernet/model.tflite`; the bundled input is 224 x 224. The model, provenance, and license live here once for both platforms. Python and private photos are offline validation inputs, never app dependencies.

## Export and fixtures

The deployed checkpoint is `mobilenetv3_custom_224_v1`: MobileNetV3 Small (alpha 0.75), 224 x 224 ImageNet-normalized RGB, 224 coordinate bins, decoded normalized corners and a sigmoid presence score. Its trained weights are exported with float32 CPU operations.

```powershell
./.local/doccornernet-env/Scripts/python.exe tools/models/export_adapted_doccornernet.py --checkpoint .local/models/mobilenetv3_custom_224_v1/best_model_inference.keras --layers .local/models/mobilenetv3_custom_224_v1/model.py --output resources/doccornernet
./.local/doccornernet-env/Scripts/python.exe tools/models/prepare_doccornernet_runtime.py --photos C:/Users/User/Downloads/samples
```

Custom layer definitions are kept beside the checkpoint as `model.py`. The local copy accepts this checkpoint's explicit `corners_first` decoder setting, preserving the existing decoding math and rejecting other layouts. Export verifies TensorFlow/TFLite tensor parity and records the checkpoint directory name in provenance. Fixture preparation checks parity on the supplied photos and writes private fixtures under `.local/doccornernet-runtime/assets/`. These checks establish deployment fidelity, not detection accuracy. Python and checkpoints are never app dependencies.

## Device parity

From `example/android`, pass the fixtures to the androidTest build only:

```powershell
./gradlew.bat :react-native-document-scanner:assembleDebugAndroidTest -PreactNativeArchitectures=arm64-v8a -PdocCornerFixtures=C:/dev/react-native-document-scanner/.local/doccornernet-runtime/assets
```

- Without `-PdocCornerFixtures`, the parity tests skip.
- Install, grant and run as described in the skill's development reference.
- Reports are written under the test package's `files/`:
  - `doccornernet-runtime/report.json`: parity.
- Read a report with `adb -s <serial> shell run-as com.margelo.nitro.documentscanner.test cat files/<path>`, save it under `.local/`, and parse it with Python `json.load()`.
