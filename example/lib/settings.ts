import { File, Paths } from 'expo-file-system'

const SETTINGS = new File(Paths.document, 'settings.json')

/** The saved settings over `defaults`: keys the defaults lack, and values of another type, are dropped, so a
 * setting added, removed or retyped since the last launch falls back to its default. */
export function loadSettings<T extends object>(defaults: T): T {
  try {
    if (!SETTINGS.exists) return defaults
    const saved: unknown = JSON.parse(SETTINGS.textSync())
    if (typeof saved !== 'object' || saved == null) return defaults
    const merged: Record<string, unknown> = { ...(defaults as Record<string, unknown>) }
    for (const [key, value] of Object.entries(saved)) {
      if (!(key in defaults)) continue
      const fallback = (defaults as Record<string, unknown>)[key]
      // An unset optional default (the JPEG quality) takes a saved number, or stays unset.
      const fits = fallback === undefined ? value == null || typeof value === 'number' : typeof value === typeof fallback
      if (fits) merged[key] = value ?? undefined
    }
    return merged as T
  } catch (error) {
    console.warn('Could not read the saved settings.', error)
    return defaults
  }
}

export function saveSettings(settings: object) {
  try {
    SETTINGS.write(JSON.stringify(settings))
  } catch (error) {
    console.warn('Could not save the settings.', error)
  }
}
