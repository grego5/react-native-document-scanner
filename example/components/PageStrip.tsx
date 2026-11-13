import { useEffect, useRef, type ComponentRef } from 'react'
import { Pressable, ScrollView, Text } from 'react-native'
import { memo } from 'react'
import { PageThumbnail, type ScanSession } from 'react-native-document-scanner'
import { styles } from '../styles'

/** The same ordered pages as the preview pager, with the current page kept in view. */
export function PageStrip({ session, pageIds, index, disabled, onSelect }: {
  session: ScanSession; pageIds: readonly string[]; index: number; disabled: boolean; onSelect: (index: number) => void
}) {
  const strip = useRef<ComponentRef<typeof ScrollView>>(null)
  useEffect(() => {
    strip.current?.scrollTo({ x: Math.max(0, index - 1) * 60, animated: true })
  }, [index])
  return (
    <ScrollView ref={strip} horizontal showsHorizontalScrollIndicator={false}
      style={styles.pageStrip} contentContainerStyle={styles.pageStripContent}>
      {pageIds.map((id, position) => (
        <Tile key={id} session={session} pageId={id} position={position}
          selected={position === index} disabled={disabled} onSelect={onSelect} />
      ))}
    </ScrollView>
  )
}

const Tile = memo(function Tile({ session, pageId, position, selected, disabled, onSelect }: {
  session: ScanSession; pageId: string; position: number; selected: boolean; disabled: boolean
  onSelect: (position: number) => void
}) {
  return (
    <Pressable disabled={disabled} onPress={() => onSelect(position)}
      accessibilityRole="button" accessibilityLabel={`Page ${position + 1}`}
      accessibilityState={{ selected, disabled }}
      style={[styles.pageThumbnail, selected && styles.pageThumbnailSelected]}>
      <PageThumbnail session={session} pageId={pageId} resizeMode="cover" style={styles.pageTileImage} />
      <Text style={styles.pageTileLabel}>{position + 1}</Text>
    </Pressable>
  )
})
