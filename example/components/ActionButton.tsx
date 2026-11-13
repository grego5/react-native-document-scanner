import { Pressable, Text, type StyleProp, type ViewStyle } from 'react-native'
import { styles } from '../styles'

export function ActionButton({ label, onPress, onLongPress, accessibilityHint, disabled, selected, style }: {
  label: string; onPress: () => void; onLongPress?: () => void; accessibilityHint?: string; disabled?: boolean
  selected?: boolean; style?: StyleProp<ViewStyle>
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      onLongPress={onLongPress}
      accessibilityHint={accessibilityHint}
      disabled={disabled}
      accessibilityState={{ disabled, selected }}
      style={({ pressed }) => [
        styles.tuningSwitch, selected && styles.activeTool, style, pressed && styles.buttonPressed, disabled && styles.buttonDisabled,
      ]}
    >
      <Text style={[styles.tuningLabel, styles.centeredLabel]} numberOfLines={1} adjustsFontSizeToFit>{label}</Text>
    </Pressable>
  )
}
