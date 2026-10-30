import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Animated, Modal, Pressable, StyleSheet, useWindowDimensions, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

/** The backdrop fades independently of the sheet, including while closing. */
export function SheetModal({ visible, onClose, children }: {
  visible: boolean
  onClose: () => void
  children: ReactNode
}) {
  const [present, setPresent] = useState(visible)
  const progress = useRef(new Animated.Value(0)).current
  const insets = useSafeAreaInsets()
  const { height } = useWindowDimensions()

  useEffect(() => {
    if (visible) setPresent(true)
    const animation = Animated.timing(progress, {
      toValue: visible ? 1 : 0,
      duration: 220,
      useNativeDriver: true,
    })
    animation.start(({ finished }) => {
      if (finished && !visible) setPresent(false)
    })
    return () => animation.stop()
  }, [visible, progress])

  return (
    <Modal visible={present} transparent animationType="none" onRequestClose={onClose}>
      <View style={local.root}>
        <Animated.View style={[StyleSheet.absoluteFill, local.backdrop, { opacity: progress }]}>
          <Pressable style={StyleSheet.absoluteFill} onPress={onClose}
            accessibilityRole="button" accessibilityLabel="Close dialog" />
        </Animated.View>
        <View pointerEvents="box-none" style={[local.safeArea, {
          paddingTop: insets.top + 16, paddingBottom: insets.bottom + 12,
          paddingLeft: insets.left + 12, paddingRight: insets.right + 12,
        }]}>
          <Animated.View accessibilityViewIsModal style={[local.sheet, {
            transform: [{ translateY: progress.interpolate({ inputRange: [0, 1], outputRange: [height, 0] }) }],
          }]}>
            {present && children}
          </Animated.View>
        </View>
      </View>
    </Modal>
  )
}

const local = StyleSheet.create({
  root: { flex: 1 },
  backdrop: { backgroundColor: '#00000088' },
  safeArea: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '100%', flexShrink: 1 },
})
