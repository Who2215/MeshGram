[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [string]$ApkPath = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\release\app-release.apk'),
    [string]$PackageName = 'com.meshchat.app',
    [string]$ActivityName = '.MainActivity',
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'H:\Android\Sdk' }),
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb was not found at $adb"
}

$buildToolsRoot = Join-Path $SdkRoot 'build-tools'
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
    Select-Object -First 1
if (-not $buildTools) {
    throw "Android build-tools were not found under $buildToolsRoot"
}

$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner)) {
    throw "apksigner was not found at $apksigner"
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path

function Get-ApkCertificateSha256([string]$Path) {
    $output = & $apksigner verify --print-certs $Path 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed: $output"
    }
    $match = [regex]::Match(($output -join "`n"), 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if (-not $match.Success) {
        throw "Could not read the signing certificate from $Path"
    }
    return $match.Groups[1].Value.ToLowerInvariant()
}

$deviceLine = (& $adb devices) | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device(?:\s|$)" }
if (-not $deviceLine) {
    throw "ADB device $Serial is not connected and authorized"
}

$candidateCertificate = Get-ApkCertificateSha256 $resolvedApk
$installedPathLine = (& $adb -s $Serial shell pm path $PackageName 2>$null | Select-Object -First 1)
$temporaryInstalledApk = Join-Path $env:TEMP "meshgram-installed-$PID.apk"

try {
    if ($installedPathLine -match '^package:(.+)$') {
        & $adb -s $Serial pull $matches[1].Trim() $temporaryInstalledApk | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not copy the currently installed APK for certificate comparison'
        }
        $installedCertificate = Get-ApkCertificateSha256 $temporaryInstalledApk
        if ($installedCertificate -ne $candidateCertificate) {
            throw "Signing certificate mismatch. Installed=$installedCertificate candidate=$candidateCertificate"
        }
        Write-Host "Certificate match: $candidateCertificate"
    } else {
        Write-Host 'MeshGram is not installed; certificate comparison was skipped.'
    }

    if (-not $Install) {
        Write-Host 'Validation only. Pass -Install to perform the data-preserving update and launch smoke test.'
        return
    }

    $installArguments = if ($installedPathLine) { @('install', '-r', $resolvedApk) } else { @('install', $resolvedApk) }
    $installOutput = & $adb -s $Serial @installArguments 2>&1
    if ($LASTEXITCODE -ne 0 -or $installOutput -notcontains 'Success') {
        throw "APK installation failed: $installOutput"
    }

    & $adb -s $Serial logcat -c | Out-Null
    & $adb -s $Serial shell am force-stop $PackageName | Out-Null
    $startOutput = & $adb -s $Serial shell am start -W -n "$PackageName/$ActivityName" 2>&1
    $startText = $startOutput -join "`n"
    if ($LASTEXITCODE -ne 0 -or $startText -notmatch 'Status:\s+ok') {
        throw "Activity launch failed: $startText"
    }

    Start-Sleep -Seconds 3
    $appProcess = (& $adb -s $Serial shell pidof -s $PackageName).Trim()
    if (-not $appProcess) {
        throw 'MeshGram process is not alive after launch'
    }

    $crashLog = (& $adb -s $Serial logcat -b crash -d -v brief 2>&1) -join "`n"
    if ($crashLog -match [regex]::Escape($PackageName) -or $crashLog -match 'FATAL EXCEPTION|ANR in') {
        throw "Crash or ANR detected after launch:`n$crashLog"
    }

    $packageState = (& $adb -s $Serial shell dumpsys package $PackageName) |
        Select-String 'versionCode=|versionName=|lastUpdateTime' |
        ForEach-Object { $_.Line.Trim() }
    Write-Host 'Release smoke test passed.'
    $packageState | ForEach-Object { Write-Host $_ }
    Write-Host "Process: $appProcess"
} finally {
    if (Test-Path -LiteralPath $temporaryInstalledApk) {
        Remove-Item -LiteralPath $temporaryInstalledApk -Force
    }
}
