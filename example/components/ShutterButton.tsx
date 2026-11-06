import { Pressable, View } from 'react-native'
import { styles } from '../styles'

export function ShutterButton({ disabled, onPress }: { disabled: boolean; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="Take photo"
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [styles.shutter, pressed && styles.shutterPressed, disabled && styles.shutterDisabled]}
    >
      <View style={styles.shutterInner} />
    </Pressable>
  )
}
