import { NitroModules } from 'react-native-nitro-modules'
import { useCallback, useEffect, useState } from 'react'
import type { CameraPermission as CameraPermissionSpec, CameraPermissionStatus } from './CameraPermission.nitro'
import { scannerOperation, scannerValue } from './ScannerError'

/** The app's camera permission: read, request and observe it with or without a mounted Camera view. */
const nativePermission = NitroModules.createHybridObject<CameraPermissionSpec>('CameraPermission')
export const CameraPermission = {
  getStatus: () => scannerValue(() => nativePermission.getStatus(), 'permission/unavailable'),
  request: async () => (await scannerOperation(() => nativePermission.requestResult(), 'permission/request-failed')).status,
  addStatusListener: (listener: (status: CameraPermissionStatus) => void) =>
    scannerValue(() => nativePermission.addStatusListener(listener), 'permission/unavailable'),
}

/** The current camera permission status, kept up to date, and a function that requests access. */
export function useCameraPermission() {
  const [status, setStatus] = useState<CameraPermissionStatus>(() => CameraPermission.getStatus())

  useEffect(() => {
    const remove = CameraPermission.addStatusListener(setStatus)
    // The status may have changed between the first render and subscribing.
    setStatus(CameraPermission.getStatus())
    return remove
  }, [])

  const request = useCallback(async () => {
    const next = await CameraPermission.request()
    setStatus(next)
    return next
  }, [])

  return { status, request }
}
