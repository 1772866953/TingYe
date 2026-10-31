param([switch]$DryRun)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\')
$androidVersion = [regex]::Match((Get-Content -LiteralPath (Join-Path $workspaceRoot 'TingYe/app/build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$desktopVersion = (Get-Content -LiteralPath (Join-Path $workspaceRoot 'TingYeDesktop/package.json') -Raw | ConvertFrom-Json).version
if (-not $androidVersion -or -not $desktopVersion) { throw '无法确认当前版本，未清理。' }
$androidRelease = Join-Path $workspaceRoot "releases/$androidVersion"
$desktopRelease = Join-Path $workspaceRoot "releases/听页听读工坊-$desktopVersion"
$required = @(
    (Join-Path $androidRelease "TingYe-$androidVersion-release.apk"),
    (Join-Path $androidRelease 'release-mapping.txt'),
    (Join-Path $desktopRelease "TingYe-Aligner-$desktopVersion-Setup.exe"),
    (Join-Path $workspaceRoot 'signing/tingye-release.jks'),
    (Join-Path $workspaceRoot 'signing/release.properties')
)
foreach ($file in $required) { if (-not (Test-Path -LiteralPath $file)) { throw "当前交付文件或签名缺失，未清理：$file" } }
$protected = @('signing', '.git', 'work/听读原始音频', 'TingYe/app/src', 'TingYe/app/schemas', 'TingYeDesktop/src', 'TingYeDesktop/build', 'qa/desktop', 'qa/fixtures', 'tools/jdk17', 'tools/android-sdk', 'tools/gradle-8.13', 'tools/video-tools') | ForEach-Object { [IO.Path]::GetFullPath((Join-Path $workspaceRoot $_)) }
$protected += @($androidRelease, $desktopRelease)
$removedBytes = 0L
function Get-OrdinaryDirectories([string]$base) {
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($base)
    while ($pending.Count) {
        $parent = $pending.Pop()
        foreach ($directory in @(Get-ChildItem -LiteralPath $parent -Directory -Force)) {
            if (($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $directory.Name -eq '.git') { continue }
            $directory.FullName
            $pending.Push($directory.FullName)
        }
    }
}
function Remove-Disposable([string]$relative) {
    $absolute = [IO.Path]::GetFullPath((Join-Path $workspaceRoot $relative))
    if (-not $absolute.StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "清理路径超出工作区：$absolute" }
    if (-not (Test-Path -LiteralPath $absolute)) { return }
    $resolved = (Resolve-Path -LiteralPath $absolute).ProviderPath
    if (-not $resolved.Equals($absolute, [StringComparison]::OrdinalIgnoreCase)) { throw '实际清理路径与预期不一致' }
    foreach ($keep in $protected) {
        if ($keep.Equals($absolute, [StringComparison]::OrdinalIgnoreCase) -or $keep.StartsWith($absolute + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "清理范围包含开发资料或交付文件：$absolute" }
    }
    $item = Get-Item -LiteralPath $absolute -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '不清理链接目录' }
    if ($item.PSIsContainer) {
        if (Get-ChildItem -LiteralPath $absolute -Recurse -Force -Attributes ReparsePoint) { throw "清理目录中包含链接：$absolute" }
        $bytes = (Get-ChildItem -LiteralPath $absolute -Recurse -Force -File | Measure-Object Length -Sum).Sum
    } else { $bytes = $item.Length }
    if ($DryRun) { Write-Output "预览：$relative" }
    else { Remove-Item -LiteralPath $absolute -Recurse -Force }
    $script:removedBytes += $bytes
}
$results = Join-Path $workspaceRoot 'qa/results'
if (Test-Path -LiteralPath $results) {
    # setup-progress.cjs regenerates these two fixtures; other misplaced source
    # files still stop cleanup and must first be moved out of results.
    $generatedHarness = '^' + [regex]::Escape((Join-Path $results 'setup-progress')) + '\\run-[^\\]+\\(?:harness\.ps1|中文 progress\.py)$'
    $sourceFiles = Get-ChildItem -LiteralPath $results -Recurse -Force -File |
        Where-Object { $_.Extension -in @('.cjs','.mjs','.js','.py','.ps1','.kt') -and $_.FullName -notmatch $generatedHarness }
    if ($sourceFiles) { throw '测试结果目录中还有源代码，请先整理到 qa/desktop 后再清理。' }
}
foreach ($relative in @('archive', '.build-cache', 'qa/results', 'TingYe/.gradle', 'TingYe/.kotlin', 'TingYe/build', 'TingYe/app/build', 'TingYeDesktop/dist', 'TingYeDesktop/release', 'TingYeDesktop/node_modules/.cache', 'TingYeDesktop/node_modules/.vite', 'TingYeDesktop/node_modules/.vite-temp')) {
    Remove-Disposable $relative
}
if ((Test-Path -LiteralPath (Join-Path $workspaceRoot 'tools/desktop-asr')) -and
    -not (Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'tools/desktop-asr') -Force)) {
    Remove-Disposable 'tools/desktop-asr'
}
foreach ($directory in @(Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'releases') -Directory -Force)) {
    if ($directory.FullName -notin @($androidRelease, $desktopRelease)) { Remove-Disposable ($directory.FullName.Substring($workspaceRoot.Length + 1)) }
}
foreach ($directory in @(Get-OrdinaryDirectories $workspaceRoot | Where-Object { [IO.Path]::GetFileName($_) -eq '__pycache__' } | Sort-Object Length -Descending)) {
    Remove-Disposable ($directory.Substring($workspaceRoot.Length + 1))
}
$verb = if ($DryRun) { '预计清理' } else { '已清理' }
Write-Output "$verb $([Math]::Round($removedBytes / 1GB, 2)) GiB；当前源码、签名、工具链、音频及最新版交付文件保留。"
