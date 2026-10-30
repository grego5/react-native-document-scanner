import type { ExpoConfig } from 'expo/config';

const androidAbi = process.env.DOCUMENT_SCANNER_ANDROID_ABI ?? 'arm64-v8a';
if (androidAbi !== 'arm64-v8a' && androidAbi !== 'x86_64') {
  throw new Error(
    `Unsupported Android ABI "${androidAbi}". Use arm64-v8a or x86_64 for an emulator.`,
  );
}

const config: ExpoConfig = {
  name: 'Document Scanner Camera',
  slug: 'react-native-document-scanner-camera',
  scheme: 'document-scanner',
  version: '0.1.0',
  orientation: 'default',
  userInterfaceStyle: 'automatic',
  plugins: [
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
        android: {
          cmakeVersion: '4.1.2',
          buildArchs: [androidAbi],
        },
      },
    ],
  ],
  android: {
    package: 'dev.reactnativedocumentscanner.example',
  },
};

export default config;

