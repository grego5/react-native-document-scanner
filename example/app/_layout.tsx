import { Stack } from 'expo-router'
import { StatusBar, View } from 'react-native'
import { colors, styles } from '../styles'

export default function RootLayout() {
  return (
    <View style={styles.screen}>
      <StatusBar barStyle="light-content" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }} />
    </View>
  )
}
