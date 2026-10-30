import { useEffect, useRef, type ComponentRef } from 'react'
import { Image, Pressable, ScrollView, Text } from 'react-native'
import { styles } from '../styles'

export interface PageThumbnail { id: string; uri: string }

/** The same ordered pages as the preview pager, with the current page kept in view. */
export function PageStrip({ pages, index, disabled, onSelect }: {
  pages: PageThumbnail[]; index: number; disabled: boolean; onSelect: (index: number) => void
}) {
  const strip = useRef<ComponentRef<typeof ScrollView>>(null)
  useEffect(() => {
    strip.current?.scrollTo({ x: Math.max(0, index - 1) * 60, animated: true })
  }, [index])
  return (
    <ScrollView ref={strip} horizontal showsHorizontalScrollIndicator={false}
      style={styles.pageStrip} contentContainerStyle={styles.pageStripContent}>
      {pages.map((page, position) => (
        <Pressable key={page.id} disabled={disabled} onPress={() => onSelect(position)}
          accessibilityRole="button" accessibilityLabel={`Page ${position + 1}`}
          accessibilityState={{ selected: position === index, disabled }}
          style={[styles.pageThumbnail, position === index && styles.pageThumbnailSelected]}>
          <Image source={{ uri: page.uri }} resizeMethod="resize" resizeMode="cover" style={styles.pageTileImage} />
          <Text style={styles.pageTileLabel}>{position + 1}</Text>
        </Pressable>
      ))}
    </ScrollView>
  )
}
