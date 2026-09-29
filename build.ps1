$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$platform = if ($env:ANDROID_JAR) { $env:ANDROID_JAR } else { Join-Path $sdk 'platforms\android-35\android.jar' }
$tools = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else { Join-Path $sdk 'build-tools\35.0.0' }
if (-not (Test-Path -LiteralPath $platform) -and -not $env:ANDROID_JAR) {
    $platform = Join-Path $sdk 'platforms\android-36\android.jar'
}
$aapt = Join-Path $tools 'aapt.exe'
$d8 = Join-Path $tools 'd8.bat'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$keystore = Join-Path $env:USERPROFILE '.android\debug.keystore'

Remove-Item -LiteralPath 'classes', 'dex' -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath 'classes.dex', 'unsigned.apk', 'aligned.apk' -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path 'classes', 'dex' | Out-Null

& javac -encoding UTF-8 -source 8 -target 8 -classpath $platform -d classes `
  src\com\ucnacdx2\mitvhomebridge\MainActivity.java
$classFiles = @(Get-ChildItem -LiteralPath classes -Filter '*.class' -Recurse |
  ForEach-Object { $_.FullName })
& $d8 --lib $platform --min-api 21 --output dex @classFiles
Copy-Item -LiteralPath 'dex\classes.dex' -Destination 'classes.dex'

$resourceApk = if ($env:ANDROID_FRAMEWORK_RES) { $env:ANDROID_FRAMEWORK_RES } else { $platform }
& $aapt package -f -M AndroidManifest.xml -S res -I $resourceApk -F unsigned.apk
& $aapt add unsigned.apk classes.dex
& $zipalign -f 4 unsigned.apk aligned.apk
& $apksigner sign --ks $keystore --ks-key-alias androiddebugkey `
  --ks-pass pass:android --key-pass pass:android `
  --out MiTVHomeBridge.apk aligned.apk
& $apksigner verify --verbose --print-certs MiTVHomeBridge.apk

Get-FileHash MiTVHomeBridge.apk -Algorithm SHA256
