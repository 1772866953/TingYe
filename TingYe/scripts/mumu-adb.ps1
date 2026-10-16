param(
    [ValidateSet('status','install','logcat')][string]$Action = 'status',
    [string]$Apk
)
$adb = 'C:\Software\MuMu Player 12\shell\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = 'adb.exe' }
$serial = '127.0.0.1:16384'
& $adb connect $serial | Out-Host
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
switch ($Action) {
    'status' {
        & $adb -s $serial get-state
        & $adb -s $serial shell getprop ro.build.version.sdk
        & $adb -s $serial shell getprop ro.product.model
    }
    'install' {
        if (-not $Apk) {
            $version = [regex]::Match((Get-Content (Join-Path $PSScriptRoot '..\app\build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
            $Apk = Join-Path $PSScriptRoot "..\..\releases\$version\TingYe-$version-release.apk"
        }
        if (-not (Test-Path -LiteralPath $Apk)) { throw "APK 未找到：$Apk" }
        & $adb -s $serial install -r $Apk
    }
    'logcat' { & $adb -s $serial logcat -s AndroidRuntime:E Media3:W TingYe:D }
}
