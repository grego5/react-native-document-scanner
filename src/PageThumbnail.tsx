import { getHostComponent } from 'react-native-nitro-modules'
import type { ViewProps } from 'react-native'
import { getNativeScanSession, type ScanSession } from './ScanSession'
import type { PageThumbnailViewMethods, PageThumbnailViewProps } from './PageThumbnail.nitro'
import PageThumbnailViewConfig from '../nitrogen/generated/shared/json/PageThumbnailViewConfig.json'

const NativePageThumbnail = getHostComponent<PageThumbnailViewProps, PageThumbnailViewMethods>(
  'PageThumbnailView', () => PageThumbnailViewConfig,
)

export interface PageThumbnailProps extends Omit<PageThumbnailViewProps, 'hybridRef' | 'session'>, ViewProps {
  session: ScanSession
}

/** A native session thumbnail; processing status belongs to the session snapshot. */
export function PageThumbnail({ session, pageId, resizeMode = 'cover', ...props }: PageThumbnailProps) {
  return <NativePageThumbnail {...props} session={getNativeScanSession(session)} pageId={pageId} resizeMode={resizeMode} />
}
