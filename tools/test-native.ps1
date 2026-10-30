<#
Explicit native JVM test command. Gradle may compile Kotlin, resources and plugins.
Not part of npm run check; do not invoke while native builds/compilation are prohibited.
This never assembles an APK, installs an app, starts a device test or presses a shutter.
#>
[CmdletBinding()]
param(
    [string[]]$Test = @(),
    [string]$Trace,
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$Architecture = 'arm64-v8a',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$RecompilePlugins
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$androidRoot = Join-Path $repositoryRoot 'example/android'
$wrapper = Join-Path $androidRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw 'The prepared example Android project is missing. This script does not run Expo prebuild.'
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe') -PathType Leaf)) {
    throw 'Set JAVA_HOME or pass -JavaHome with the installed Android JDK directory.'
}
$gradleCache = $env:GRADLE_USER_HOME
if ([string]::IsNullOrWhiteSpace($gradleCache)) { $gradleCache = Join-Path $env:USERPROFILE '.gradle' }
$arguments = @(
    ':react-native-document-scanner:testDebugUnitTest',
    "-PreactNativeArchitectures=$Architecture",
    '-Pkotlin.incremental=false', '-Pkotlin.compiler.execution.strategy=in-process',
    '--no-daemon', '--console=plain', '--project-cache-dir',
    (Join-Path $gradleCache 'project-caches/document-scanner-tests')
)
foreach ($filter in $Test) { $arguments += @('--tests', $filter) }
if (-not [string]::IsNullOrWhiteSpace($Trace)) {
    $tracePath = if ([IO.Path]::IsPathRooted($Trace)) { $Trace } else { Join-Path $repositoryRoot $Trace }
    if (-not (Test-Path -LiteralPath $tracePath)) { throw "Trace input does not exist: $tracePath" }
    $arguments += "-PliveQuadTrace=$tracePath"
}
if ($RecompilePlugins) { $arguments += @('--rerun-tasks', '--no-build-cache') }

$savedJavaHome = $env:JAVA_HOME
$savedGradleCache = $env:GRADLE_USER_HOME
$exitCode = 1
Push-Location -LiteralPath $androidRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $gradleCache
    Write-Output 'Running native JVM tests; Gradle compilation may occur. No APK or device work.'
    & $wrapper @arguments
    $exitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:GRADLE_USER_HOME = $savedGradleCache
    Pop-Location
}
exit $exitCode
