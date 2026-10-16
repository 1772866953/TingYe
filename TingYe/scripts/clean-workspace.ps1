$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\')
$projectRoot = Join-Path $workspaceRoot 'TingYe'
$version = [regex]::Match((Get-Content (Join-Path $projectRoot 'app\build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $version) { throw '无法确认当前版本，未清理。' }
$currentRelease = Join-Path $workspaceRoot "releases\$version\TingYe-$version-release.apk"
foreach ($required in @($currentRelease, (Join-Path $workspaceRoot "releases\$version\release-mapping.txt"),
    (Join-Path $workspaceRoot 'signing\tingye-release.jks'), (Join-Path $workspaceRoot 'signing\release.properties'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "交付文件或签名缺失，未清理：$required" }
}
$removedBytes = 0L
function RemoveDisposable([string]$path) {
    $absolute = [IO.Path]::GetFullPath($path)
    if (-not $absolute.StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "清理路径超出工作区：$absolute"
    }
    if (-not (Test-Path -LiteralPath $absolute)) { return }
    $resolved = (Resolve-Path -LiteralPath $absolute).ProviderPath
    if (-not $resolved.StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw '实际清理路径越界' }
    $item = Get-Item -LiteralPath $absolute -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '不清理链接目标' }
    if ($item.PSIsContainer) {
        $script:removedBytes += (Get-ChildItem -LiteralPath $absolute -File -Recurse -Force | Measure-Object Length -Sum).Sum
    } else { $script:removedBytes += $item.Length }
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        try {
            if (Test-Path -LiteralPath $absolute) { Remove-Item -LiteralPath $absolute -Recurse -Force }
            break
        } catch {
            if ($attempt -eq 5) { throw }
            Start-Sleep -Milliseconds 500
        }
    }
}
foreach ($relative in @('archive','work','qa\results','qa\workspace-cleanup.json',
    '.build-cache\android',
    'TingYe\PLAN.md','TingYe\TEST_RESULTS.md','TingYe\scripts\organize-workspace.ps1',
    'TingYe\.gradle','TingYe\.kotlin','TingYe\build','TingYe\app\build')) {
    RemoveDisposable (Join-Path $workspaceRoot $relative)
}
$releasesRoot = Join-Path $workspaceRoot 'releases'
foreach ($item in @(Get-ChildItem -LiteralPath $releasesRoot -Force)) {
    if ($item.Name -ne $version) { RemoveDisposable $item.FullName }
}
$gradleHome = Join-Path $workspaceRoot '.build-cache\gradle'
if (Test-Path -LiteralPath $gradleHome) {
    foreach ($item in @(Get-ChildItem -LiteralPath $gradleHome -Force)) {
        if ($item.Name -ne 'caches') { RemoveDisposable $item.FullName }
    }
    $caches = Join-Path $gradleHome 'caches'
    if (Test-Path -LiteralPath $caches) {
        foreach ($item in @(Get-ChildItem -LiteralPath $caches -Force)) {
            if ($item.Name -ne 'modules-2') { RemoveDisposable $item.FullName }
        }
    }
}
foreach ($item in @(Get-ChildItem -LiteralPath $workspaceRoot -Directory -Recurse -Force |
    Where-Object Name -eq '__pycache__' | Sort-Object { $_.FullName.Length } -Descending)) {
    RemoveDisposable $item.FullName
}
Write-Output "旧备份、记录及编译产物已清理，共 $([math]::Round($removedBytes/1GB,2)) GiB；当前开发资料与可复用依赖保留。"
