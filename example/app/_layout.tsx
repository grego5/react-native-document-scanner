import { Stack } from 'expo-router'
import { StatusBar, View } from 'react-native'
import { enableDiagnostics } from '../lib/diagnostics'
import { colors, styles } from '../styles'

// Before any camera mounts: a camera keeps the diagnostics setting it was created with.
enableDiagnostics()

export default function RootLayout() {
  return (
    <View style={styles.screen}>
      <StatusBar barStyle="light-content" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }} />
    </View>
  )
}
