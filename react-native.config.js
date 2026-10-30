module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath:
          'import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerPackage;',
        packageInstance: 'new ReactNativeDocumentScannerPackage()',
      },
      ios: null,
    },
  },
}
