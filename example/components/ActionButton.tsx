import { Pressable, Text, type StyleProp, type ViewStyle } from 'react-native'
import { styles } from '../styles'

export function ActionButton({ label, onPress, onLongPress, accessibilityHint, disabled, style }: {
  label: string; onPress: () => void; onLongPress?: () => void; accessibilityHint?: string; disabled?: boolean
  style?: StyleProp<ViewStyle>
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      onLongPress={onLongPress}
      accessibilityHint={accessibilityHint}
      disabled={disabled}
      accessibilityState={{ disabled }}
      style={({ pressed }) => [styles.tuningSwitch, style, pressed && styles.buttonPressed, disabled && styles.buttonDisabled]}
    >
      <Text style={styles.tuningLabel}>{label}</Text>
    </Pressable>
  )
}
