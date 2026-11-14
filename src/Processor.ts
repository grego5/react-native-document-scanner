import { NitroModules } from 'react-native-nitro-modules';
import type { DocumentScannerProcessor } from './DocumentScannerProcessor.nitro';

/** The package's one native processor. */
export const nativeProcessor = NitroModules.createHybridObject<DocumentScannerProcessor>(
  'DocumentScannerProcessor',
);
