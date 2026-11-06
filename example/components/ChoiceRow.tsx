import { View } from 'react-native'
import { styles } from '../styles'
import { PrimaryButton } from './PrimaryButton'

/** One-of choice buttons in a row, the selected one filled. */
export function ChoiceRow<T extends string>({
  choices,
  value,
  onChange,
  disabled = false,
}: {
  choices: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
  disabled?: boolean
}) {
  return (
    <View style={styles.actionRow}>
      {choices.map((choice) => (
        <PrimaryButton
          key={choice.value}
          label={choice.label}
          variant={value === choice.value ? 'primary' : 'secondary'}
          style={styles.actionButton}
          onPress={() => onChange(choice.value)}
          disabled={disabled}
        />
      ))}
    </View>
  )
}
