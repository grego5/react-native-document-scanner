<#
Builds the library's instrumentation APK, installs it on one device and runs the given test classes or methods.
It stops before installing when the build fails, so a device never runs an older APK, and exits non-zero unless the
runner reports OK. It never runs LiveCameraRuntimeTest unless named, since that needs a person at the shutter.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Device,
    # Fully qualified classes or Class#method; all tests when empty.
    [string[]]$Class = @(),
    # Runs the measurements (`-e measure true`).
    [switch]$Measure,
    # Further instrumentation arguments as name=value, passed as `-e name value`.
    [string[]]$Argument = @(),
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$Architecture = 'arm64-v8a',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$androidRoot = Join-Path $repositoryRoot 'example/android'
$wrapper = Join-Path $androidRoot 'gradlew.bat'
$apk = Join-Path $repositoryRoot 'android/build/outputs/apk/androidTest/debug/react-native-document-scanner-debug-androidTest.apk'
$runner = 'com.margelo.nitro.documentscanner.test/androidx.test.runner.AndroidJUnitRunner'
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw 'The prepared example Android project is missing. This script does not run Expo prebuild.'
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe') -PathType Leaf)) {
    $JavaHome = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe') -PathType Leaf)) {
    throw 'Set JAVA_HOME or pass -JavaHome with the installed Android JDK directory.'
}

if (-not $SkipBuild) {
    $savedJavaHome = $env:JAVA_HOME
    Push-Location -LiteralPath $androidRoot
    try {
        $env:JAVA_HOME = $JavaHome
        & $wrapper ':react-native-document-scanner:assembleDebugAndroidTest' "-PreactNativeArchitectures=$Architecture" '--console=plain' '-q'
        if ($LASTEXITCODE -ne 0) { throw "The instrumentation APK did not build (exit $LASTEXITCODE); nothing was installed." }
    } finally {
        $env:JAVA_HOME = $savedJavaHome
        Pop-Location
    }
}

& adb -s $Device install -r -t $apk
if ($LASTEXITCODE -ne 0) { throw "The instrumentation APK did not install on $Device." }

$instrument = @('-w')
if ($Class.Count -gt 0) {
    $instrument += @('-e', 'class', ($Class -join ','))
} else {
    $instrument += @('-e', 'notClass', 'com.margelo.nitro.documentscanner.LiveCameraRuntimeTest')
}
if ($Measure) { $instrument += @('-e', 'measure', 'true') }
foreach ($pair in $Argument) {
    $name, $value = $pair -split '=', 2
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Instrumentation arguments are name=value: $pair" }
    $instrument += @('-e', $name, $value)
}
$output = & adb -s $Device shell am instrument @instrument $runner
$output | Write-Output
if (-not ($output -match '^OK \(')) { exit 1 }
