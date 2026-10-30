import { Pressable, Text, type StyleProp, type ViewStyle } from 'react-native'
import { styles } from '../styles'

export function TuningSwitch({ label, onPress, disabled, style }: {
  label: string; onPress: () => void; disabled?: boolean; style?: StyleProp<ViewStyle>
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      accessibilityState={{ disabled }}
      style={({ pressed }) => [styles.tuningSwitch, style, pressed && styles.buttonPressed, disabled && styles.buttonDisabled]}
    >
      <Text style={styles.tuningLabel}>{label}</Text>
    </Pressable>
  )
}
