[CmdletBinding()]
param(
    [string]$Sdk,
    [switch]$Install,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (!$Sdk) {
    $Sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path $env:LOCALAPPDATA 'NomadLauncher\android-sdk')) |
        Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms\android-36\android.jar')) } |
        Select-Object -First 1
}
if (!$Sdk) { throw 'Install Android SDK Platform 36 and Build Tools 36.0.0, or pass -Sdk.' }
$platform = Join-Path $Sdk 'platforms\android-36\android.jar'
$tools = Join-Path $Sdk 'build-tools\36.0.0'
$aapt = Join-Path $tools 'aapt2.exe'
$align = Join-Path $tools 'zipalign.exe'
$d8 = Join-Path $tools 'lib\d8.jar'
$sign = Join-Path $tools 'lib\apksigner.jar'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
foreach ($tool in @($platform, $aapt, $align, $d8, $sign)) {
    if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing SDK file: $tool" }
}
foreach ($tool in @('java', 'javac', 'jar', 'keytool')) {
    $javaDirectory = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' }
    $candidate = if ($javaDirectory) { Join-Path $javaDirectory "$tool.exe" }
    $command = if ($candidate -and (Test-Path -LiteralPath $candidate)) { $candidate } else {
        (Get-Command "$tool.exe" -ErrorAction Stop).Source
    }
    Set-Variable -Name "jdk_$tool" -Value $command
}

function Invoke-Tool([string]$File, [string[]]$Arguments) {
    & $File @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $File) exited with code $LASTEXITCODE" }
}

$out = Join-Path $root 'build'
$work = Join-Path $out ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$work\classes", "$work\generated", "$work\dex" | Out-Null
$apk = Join-Path $out 'nomad-launcher.apk'
try {
    Invoke-Tool $aapt @('compile', '--dir', "$root\app\res", '-o', "$work\resources.zip")
    Invoke-Tool $aapt @('link', '-I', $platform, '--manifest', "$root\app\AndroidManifest.xml",
        '--java', "$work\generated", '-o', "$work\unsigned.apk", "$work\resources.zip")
    $sources = @(Get-ChildItem -Recurse -Filter '*.java' -File -LiteralPath "$root\app\src","$work\generated" |
        Select-Object -ExpandProperty FullName)
    Invoke-Tool $jdk_javac (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $platform,
        '-d', "$work\classes") + $sources)
    Invoke-Tool $jdk_jar @('cf', "$work\classes.jar", '-C', "$work\classes", '.')
    Invoke-Tool $jdk_java @('-cp', $d8, 'com.android.tools.r8.D8', '--release',
        '--min-api', '28', '--lib', $platform, '--output', "$work\dex", "$work\classes.jar")
    Invoke-Tool $jdk_jar @('uf', "$work\unsigned.apk", '-C', "$work\dex", 'classes.dex')
    Invoke-Tool $align @('-f', '4', "$work\unsigned.apk", "$work\aligned.apk")

    $keyFolder = Join-Path $env:USERPROFILE '.android'
    $key = Join-Path $keyFolder 'debug.keystore'
    if (!(Test-Path -LiteralPath $key -PathType Leaf)) {
        New-Item -ItemType Directory -Force -Path $keyFolder | Out-Null
        Invoke-Tool $jdk_keytool @('-genkeypair', '-keystore', $key, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
    }
    # The Android debug key is for local sideloading. Keep release signing separate.
    Invoke-Tool $jdk_java @('-jar', $sign, 'sign', '--ks', $key, '--ks-key-alias', 'androiddebugkey',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--v4-signing-enabled', 'false',
        '--out', "$work\signed.apk", "$work\aligned.apk")
    Invoke-Tool $jdk_java @('-jar', $sign, 'verify', '--verbose', "$work\signed.apk")
    Copy-Item -Force -LiteralPath "$work\signed.apk" -Destination $apk
} finally {
    $resolved = (Resolve-Path -LiteralPath $work).Path
    if ((Split-Path $resolved -Parent) -eq (Resolve-Path -LiteralPath $out).Path) {
        Remove-Item -Recurse -Force -LiteralPath $resolved
    }
}

Write-Host "APK $apk"
if ($Install) {
    if (!(Test-Path -LiteralPath $adb -PathType Leaf)) { throw 'Install Android SDK Platform Tools for adb.' }
    $target = if ($Serial) { @('-s', $Serial) } else { @() }
    Invoke-Tool $adb ($target + @('install', '-r', $apk))
}
