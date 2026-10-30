[CmdletBinding()]
param(
    [switch]$Release,
    [switch]$Emulator,
    [string]$Device,
    [ValidateRange(1, 65535)]
    [int]$Port = 8088
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$exampleRoot = Join-Path $repositoryRoot 'example'
$expoAbiVariable = 'DOCUMENT_SCANNER_ANDROID_ABI'
$packagerHostnameVariable = 'REACT_NATIVE_PACKAGER_HOSTNAME'
$androidAbi = if ($Emulator) { 'x86_64' } else { 'arm64-v8a' }

function Stop-AndroidBuild([string]$Message, [int]$Code = 1) {
    [Console]::Error.WriteLine("ERROR: $Message")
    exit $Code
}

function Get-AdbPath {
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) {
            $adbName = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
                'adb.exe'
            } else {
                'adb'
            }
            $sdkAdb = Join-Path $sdkRoot "platform-tools\$adbName"
            if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) {
                return $sdkAdb
            }
        }
    }

    $pathCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $pathCommand) {
        return $pathCommand.Source
    }
    return $null
}

function Get-OnlineDevices([string]$AdbPath) {
    if ([string]::IsNullOrWhiteSpace($AdbPath)) { return @() }
    $adbServer = Get-NetTCPConnection -State Listen -LocalPort 5037 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $adbServer) { return @() }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $AdbPath
    $startInfo.Arguments = 'devices'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $adbProcess = [System.Diagnostics.Process]::new()
    $adbProcess.StartInfo = $startInfo
    if (-not $adbProcess.Start()) { return @() }
    if (-not $adbProcess.WaitForExit(5000)) {
        $adbProcess.Kill()
        $adbProcess.WaitForExit()
        return @()
    }
    if ($adbProcess.ExitCode -ne 0) { return @() }

    $output = $adbProcess.StandardOutput.ReadToEnd()
    return @(
        $output -split "`r?`n" | ForEach-Object {
            if ([string]$_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
        }
    )
}

if (-not (Test-Path -LiteralPath $exampleRoot -PathType Container)) {
    Stop-AndroidBuild "example host was not found at $exampleRoot"
}

if (-not [string]::IsNullOrWhiteSpace($Device)) {
    if ($Release) {
        Stop-AndroidBuild 'Release mode builds the APK only; omit -Device and install the printed APK separately'
    }
    if ($Emulator -and $Device -notmatch '^emulator-\d+$') {
        Stop-AndroidBuild 'x86_64 builds target an emulator; omit -Device to build without installing or use an emulator serial'
    }
    if (-not $Emulator -and $Device -match '^emulator-\d+$') {
        Stop-AndroidBuild 'emulator targets must use -Emulator so the build selects x86_64'
    }
}

$hadSavedAbi = Test-Path "Env:$expoAbiVariable"
$savedAbi = [Environment]::GetEnvironmentVariable($expoAbiVariable)
$hadSavedPackagerHostname = Test-Path "Env:$packagerHostnameVariable"
$savedPackagerHostname = [Environment]::GetEnvironmentVariable($packagerHostnameVariable)
$savedLocation = Get-Location
try {
    [Environment]::SetEnvironmentVariable($expoAbiVariable, $androidAbi, 'Process')
    if ($Emulator) {
        [Environment]::SetEnvironmentVariable($packagerHostnameVariable, '10.0.2.2', 'Process')
    }
    Set-Location -LiteralPath $exampleRoot

    $metroIsResponsive = $false
    $portListener = if (-not $Release) {
        Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    } else { $null }
    if ($null -ne $portListener) {
        $metroStatus = (& curl.exe --noproxy '*' --silent --max-time 10 "http://127.0.0.1:$Port/status") -join "`n"
        $metroIsResponsive = $LASTEXITCODE -eq 0 -and $metroStatus -match 'packager-status:running'
    }

    $variant = if ($Release) { 'release' } else { 'debug' }
    Write-Output "Preparing Android $variant host for $androidAbi"
    & npx expo prebuild --platform android --no-install
    if ($LASTEXITCODE -ne 0) {
        Stop-AndroidBuild 'Expo Android prebuild failed' $LASTEXITCODE
    }

    $androidRoot = Join-Path $exampleRoot 'android'
    Set-Location -LiteralPath $androidRoot
    $buildTask = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    # Unit tests use the debug variant, as in test-native.ps1; the host may omit release test tasks.
    $testTask = ':react-native-document-scanner:testDebugUnitTest'
    # One Gradle run verifies native unit tests and generated bindings as part of the app build.
    # Compile debug device tests too; running them remains an explicit test-device.ps1 operation.
    $buildTasks = @($testTask)
    if (-not $Release) { $buildTasks += ':react-native-document-scanner:compileDebugAndroidTestKotlin' }
    $buildTasks += $buildTask
    & .\gradlew.bat @buildTasks "-PreactNativeArchitectures=$androidAbi"
    if ($LASTEXITCODE -ne 0) {
        Stop-AndroidBuild 'Gradle Android build or tests failed' $LASTEXITCODE
    }
    Set-Location -LiteralPath $exampleRoot

    $apkPath = Join-Path $androidRoot "app\build\outputs\apk\$variant\app-$variant.apk"
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        Stop-AndroidBuild "Gradle completed but the APK was not found at $apkPath"
    }
    Write-Output "Built APK: $apkPath"
    if ($Release) {
        Write-Output 'Release APK includes the JavaScript bundle; Metro is not required. Build only; no automatic install.'
        return
    }

    $adb = Get-AdbPath
    $onlineDevices = @(Get-OnlineDevices $adb)
    if ([string]::IsNullOrWhiteSpace($Device)) {
        $candidates = if ($Emulator) {
            @($onlineDevices | Where-Object { $_ -match '^emulator-\d+$' })
        } else {
            @($onlineDevices | Where-Object { $_ -notmatch '^emulator-\d+$' })
        }
        $Device = $candidates | Select-Object -First 1
    } elseif ($Device -notin $onlineDevices) {
        $Device = $null
    }

    if ([string]::IsNullOrWhiteSpace($Device)) {
        Write-Output 'No compatible online device or responsive ADB server; skipped install.'
    } else {
        $deviceAbiOutput = & $adb -s $Device shell getprop ro.product.cpu.abi 2>$null | Select-Object -First 1
        $deviceAbi = if ($null -eq $deviceAbiOutput) { '' } else { ([string]$deviceAbiOutput).Trim() }
        if ($LASTEXITCODE -ne 0 -or $deviceAbi -ne $androidAbi) {
            Write-Output "Connected device '$Device' ABI '$deviceAbi' does not match $androidAbi; skipped install."
        } else {
            & $adb -s $Device install -r $apkPath
            if ($LASTEXITCODE -ne 0) {
                Stop-AndroidBuild "Could not install the development host on '$Device'"
            }

            if ($metroIsResponsive) {
                Write-Output "Reusing Metro already running on port $Port"
                & $adb -s $Device reverse "tcp:$Port" "tcp:$Port"
                if ($LASTEXITCODE -ne 0) {
                    Stop-AndroidBuild "Could not forward Metro port $Port to '$Device'"
                }
                $packagerUrl = if ($Emulator) { "http://10.0.2.2:$Port" } else { "http://localhost:$Port" }
                $devClientUrl = "exp+react-native-document-scanner-camera://expo-development-client/?url=$([Uri]::EscapeDataString($packagerUrl))"
                $component = 'dev.reactnativedocumentscanner.example/.MainActivity'
                & $adb -s $Device shell am start -a android.intent.action.VIEW -n $component -d $devClientUrl
                if ($LASTEXITCODE -ne 0) {
                    Stop-AndroidBuild "Could not open the development client on '$Device'"
                }
            } else {
                Write-Output "Installed on '$Device'; start Metro with npm run start:lan before opening the app."
            }
        }
    }
} finally {
    Set-Location -LiteralPath $savedLocation
    if ($hadSavedAbi) {
        [Environment]::SetEnvironmentVariable($expoAbiVariable, $savedAbi, 'Process')
    } else {
        [Environment]::SetEnvironmentVariable($expoAbiVariable, $null, 'Process')
    }
    if ($hadSavedPackagerHostname) {
        [Environment]::SetEnvironmentVariable($packagerHostnameVariable, $savedPackagerHostname, 'Process')
    } else {
        [Environment]::SetEnvironmentVariable($packagerHostnameVariable, $null, 'Process')
    }
}
