[CmdletBinding()]
param(
    [string]$Device,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$wrapper = Join-Path $repositoryRoot 'example/android/gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper)) { throw 'Run the example Android prebuild once to obtain the Gradle wrapper.' }
if (-not $JavaHome) { $JavaHome = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Pass -JavaHome with the Android Studio JDK.' }
$savedJava = $env:JAVA_HOME
$savedSdk = $env:ANDROID_HOME
try {
    $env:JAVA_HOME = $JavaHome
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    & $wrapper -p (Join-Path $PSScriptRoot 'resolution-check') ':app:assembleDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Resolution check APK build failed; nothing was installed.' }
    $output = Join-Path $repositoryRoot '.local/resolution-check'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $apk = Join-Path $output 'scanner-resolution-check.apk'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'resolution-check/app/build/outputs/apk/debug/app-debug.apk') -Destination $apk -Force
    Write-Output "Standalone APK (no Metro): $apk"
    if ($Device) {
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
        & $adb -s $Device install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'Could not install the resolution check APK.' }
        & $adb -s $Device shell am start -n 'dev.documentscanner.resolutioncheck/.ResolutionCheckActivity'
        if ($LASTEXITCODE -ne 0) { throw 'Could not open the resolution check app.' }
    }
} finally {
    $env:JAVA_HOME = $savedJava
    $env:ANDROID_HOME = $savedSdk
}
