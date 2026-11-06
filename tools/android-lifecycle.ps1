[CmdletBinding()]
param(
    [string]$Device,
    [string]$Package = 'dev.reactnativedocumentscanner.example',
    [string]$PackagerUrl = 'http://10.0.2.2:8088',
    [ValidateRange(5, 180)]
    [int]$TimeoutSeconds = 40,
    [ValidateRange(1, 30)]
    [int]$BackgroundSeconds = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$logTag = 'RNDocumentScannerCamera'

function Stop-LifecycleCheck([string]$Message, [int]$Code = 1) {
    [Console]::Error.WriteLine("FAIL: $Message")
    exit $Code
}

function Get-AdbPath {
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) {
            $adbName = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'adb.exe' } else { 'adb' }
            $sdkAdb = Join-Path $sdkRoot "platform-tools\$adbName"
            if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) { return $sdkAdb }
        }
    }

    $pathCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $pathCommand) { return $pathCommand.Source }
    Stop-LifecycleCheck 'adb was not found; install Android platform-tools and expose ANDROID_HOME or add adb to PATH'
}

$adb = Get-AdbPath
$deviceLines = & $adb devices
if ($LASTEXITCODE -ne 0) { Stop-LifecycleCheck 'adb device discovery failed' }
$onlineDevices = @(
    $deviceLines | ForEach-Object {
        if ([string]$_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
    }
)
if ([string]::IsNullOrWhiteSpace($Device)) {
    if ($onlineDevices.Count -ne 1) {
        Stop-LifecycleCheck "expected one online device; found $($onlineDevices.Count). Pass -Device to select one"
    }
    $Device = $onlineDevices[0]
} elseif ($onlineDevices -notcontains $Device) {
    Stop-LifecycleCheck "device '$Device' is not online"
}

$component = "$Package/.MainActivity"
$devClientUrl = "exp+react-native-document-scanner-camera://expo-development-client/?url=$([Uri]::EscapeDataString($PackagerUrl))"
Write-Output "Checking camera lifecycle on $Device ($Package)"
Write-Output "Clearing Logcat, opening $PackagerUrl, then backgrounding and foregrounding the host."

& $adb -s $Device logcat -c
if ($LASTEXITCODE -ne 0) { Stop-LifecycleCheck 'could not clear Logcat' }
& $adb -s $Device shell am force-stop $Package
if ($LASTEXITCODE -ne 0) { Stop-LifecycleCheck "could not stop $Package" }
& $adb -s $Device shell am start -a android.intent.action.VIEW -n $component -d $devClientUrl
if ($LASTEXITCODE -ne 0) { Stop-LifecycleCheck "could not launch $component; install the debug host first" }

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$logOutput = ''
do {
    Start-Sleep -Seconds 1
    $logOutput = (& $adb -s $Device logcat -d -s "${logTag}:D") -join "`n"
    if ($logOutput -match 'host attached') { break }
} while ([DateTime]::UtcNow -lt $deadline)

if ($logOutput -notmatch 'host attached') {
    Write-Output $logOutput
    Write-Output 'Camera view attach: FAIL'
    Write-Output 'Activity background/foreground events: NOT RUN'
    Write-Output 'CameraX bind/state: NOT OBSERVED'
    Stop-LifecycleCheck "camera view did not attach within $TimeoutSeconds seconds; confirm the app's Metro server is available"
}

& $adb -s $Device shell input keyevent KEYCODE_HOME
if ($LASTEXITCODE -ne 0) { Stop-LifecycleCheck 'could not send the Home key' }
Start-Sleep -Seconds $BackgroundSeconds
& $adb -s $Device shell am start -n $component
if ($LASTEXITCODE -ne 0) { Stop-LifecycleCheck 'could not bring the host back to the foreground' }
Start-Sleep -Seconds 3

$logOutput = (& $adb -s $Device logcat -d -s "${logTag}:D") -join "`n"
$logLines = @($logOutput -split "`n")
$viewMatch = [regex]::Match($logOutput, 'view=([a-fA-F0-9]+) host attached')
$viewId = if ($viewMatch.Success) { $viewMatch.Groups[1].Value } else { $null }
$pauseIndex = -1
$resumeIndex = -1
$stopIndex = -1
$rebindIndex = -1
$reopenIndex = -1
for ($index = 0; $index -lt $logLines.Count; $index++) {
    $line = $logLines[$index]
    if ($line -match 'activity lifecycle event=ON_PAUSE') { $pauseIndex = $index }
    if ($line -match 'activity lifecycle event=ON_RESUME') { $resumeIndex = $index }
    if ($null -ne $viewId -and $line -match "view=$([regex]::Escape($viewId)) camera stop reason=window-hidden bound=true") { $stopIndex = $index }
    if ($null -ne $viewId -and $resumeIndex -ge 0 -and $index -gt $resumeIndex -and $line -match "view=$([regex]::Escape($viewId)) CameraX bound") { $rebindIndex = $index }
    if ($null -ne $viewId -and $rebindIndex -ge 0 -and $index -gt $rebindIndex -and $line -match "view=$([regex]::Escape($viewId)) CameraX state=.*type=OPEN([,}]|$)") { $reopenIndex = $index }
}
$sameView = $null -ne $viewId -and $logOutput -match "view=$([regex]::Escape($viewId)) host visible"
$viewDropped = $null -ne $viewId -and $logOutput -match "view=$([regex]::Escape($viewId)) view dropped"
$viewDetached = $null -ne $viewId -and $logOutput -match "view=$([regex]::Escape($viewId)) view detached"
$cameraOpenedInitially = $null -ne $viewId -and $logOutput -match "view=$([regex]::Escape($viewId)) CameraX state=.*type=OPEN([,}]|$)"
$lifecyclePassed = $viewMatch.Success -and $cameraOpenedInitially -and $pauseIndex -ge 0 -and $stopIndex -gt $pauseIndex -and $resumeIndex -gt $stopIndex -and $rebindIndex -gt $resumeIndex -and $reopenIndex -gt $rebindIndex -and $sameView -and -not $viewDropped -and -not $viewDetached

Write-Output ''
Write-Output 'Camera lifecycle log:'
$logOutput -split "`n" | Where-Object {
    $_ -match 'camera (start|stop)|CameraX (bound|state)|activity lifecycle event=|view (attached|detached)|host (attached|detached|visible)'
} | ForEach-Object { Write-Output $_ }
Write-Output ''
Write-Output ("Same view stayed attached: {0}" -f $(if ($sameView -and -not $viewDropped -and -not $viewDetached) { 'PASS' } else { 'FAIL' }))
Write-Output ("CameraX opened, stopped, and reopened: {0}" -f $(if ($lifecyclePassed) { 'PASS' } else { 'FAIL' }))

if (-not $lifecyclePassed) {
    Stop-LifecycleCheck 'expected initial OPEN, ON_PAUSE, bound camera stop, ON_RESUME, same-view rebind, and reopened OPEN without view detach/drop'
}
Write-Output 'PASS: same camera view closed and reopened across Activity background and foreground.'
