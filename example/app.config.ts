import type { ExpoConfig } from 'expo/config';

const packageName = process.env.EXPO_PUBLIC_PACKAGE_NAME ?? 'dev.grego5.docscanner';
const appName = process.env.EXPO_PUBLIC_APP_NAME ?? 'Doc Scanner';
const slug = process.env.EXPO_PUBLIC_SLUG ?? 'react-native-document-scanner';
const scheme = process.env.EXPO_PUBLIC_URL_SCHEME ?? 'react-native-document-scanner';

const androidAbi = process.env.DOCUMENT_SCANNER_ANDROID_ABI ?? 'arm64-v8a';
if (androidAbi !== 'arm64-v8a' && androidAbi !== 'x86_64') {
  throw new Error(
    `Unsupported Android ABI "${androidAbi}". Use arm64-v8a or x86_64 for an emulator.`,
  );
}

const cornerDetectionModel =
  process.env.DOCUMENT_SCANNER_CORNER_MODEL || './assets/doccornernet/model.tflite';

const config: ExpoConfig = {
  name: appName,
  slug,
  scheme,
  version: '1.0.0',
  orientation: 'default',
  userInterfaceStyle: 'automatic',
  plugins: [
    ['react-native-document-scanner', { cornerDetectionModel }],
    'expo-dev-client',
    'expo-router',
    'expo-system-ui',
    'expo-sharing',
    [
      'expo-media-library',
      {
        photosPermission: false,
        savePhotosPermission:
          'Allow Document Scanner to save captured photos to your photo library.',
        granularPermissions: ['photo'],
      },
    ],
    [
      'expo-build-properties',
      {
        ios: {
          deploymentTarget: '18.0',
        },
        android: {
          cmakeVersion: '4.1.2',
          buildArchs: [androidAbi],
        },
      },
    ],
  ],
  ios: {
    bundleIdentifier: packageName,
    supportsTablet: true,
    infoPlist: {
      NSCameraUsageDescription: 'Document Scanner uses the camera to scan documents.',
      ITSAppUsesNonExemptEncryption: false,
    },
  },
  android: {
    package: packageName,
  },
};

export default config;

