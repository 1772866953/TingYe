param([switch]$Offline, [switch]$Test)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..'))
$env:JAVA_HOME = Join-Path $workspaceRoot 'tools\jdk17\jdk-17.0.20.1+1'
$env:ANDROID_HOME = Join-Path $workspaceRoot 'tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $workspaceRoot '.build-cache\android'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
$env:GRADLE_USER_HOME = Join-Path $workspaceRoot '.build-cache\gradle'
$gradle = Join-Path $workspaceRoot 'tools\gradle-8.13\bin\gradle.bat'
$arguments = @('assembleRelease', '--no-parallel', '-Pkotlin.compiler.execution.strategy=in-process')
if ($Test) { $arguments = @('testReleaseUnitTest') + $arguments }
if ($Offline) { $arguments += '--offline' }
Push-Location $projectRoot
try {
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) { throw '构建失败，未更新交付文件。' }
    $version = [regex]::Match((Get-Content 'app\build.gradle.kts' -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    if (-not $version) { throw '未找到版本号。' }
    $releaseDirectory = Join-Path $workspaceRoot "releases\$version"
    New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
    Copy-Item 'app\build\outputs\apk\release\app-release.apk' (Join-Path $releaseDirectory "TingYe-$version-release.apk")
    Copy-Item 'app\build\outputs\mapping\release\mapping.txt' (Join-Path $releaseDirectory 'release-mapping.txt')
} finally { Pop-Location }
