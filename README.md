# React Native Document Scanner

Offline document scanning for React Native on Android and iOS. Capture photos with live corner guidance, edit crops and filters, and export images or PDFs.

```sh
npm install react-native-document-scanner react-native-nitro-modules
```

Rebuild the host app after installation. Expo requires a development build.

The package ships no corner detection model. Document scanning needs a float32 TFLite model; without one the camera reports the scanner unavailable, and `mode="photo"` works without it.

For Expo prebuild, add `['react-native-document-scanner', { cornerDetectionModel: './path/to/model.tflite' }]` to your app's `plugins` array before `expo-build-properties` if you use it. The plugin registers the bundled OpenCV pod on iOS and copies the model into both apps, where `Camera` and imports use it by default. Without the plugin, pass a model through the camera's `cornerDetectionModel` prop.

The Expo example uses one scanning workflow on Android, iPhone, and iPad, including crop and filter editing, saving rendered pages to Photos, and sharing PDFs.

- [Integration and API guide](https://github.com/grego5/react-native-document-scanner/blob/main/.agents/skills/docs/references/integration.md)
- [iOS host setup](https://github.com/grego5/react-native-document-scanner/blob/main/.agents/skills/docs/references/ios/setup.md)
- [Maintainer table of contents](https://github.com/grego5/react-native-document-scanner/blob/main/.agents/skills/docs/SKILL.md)
