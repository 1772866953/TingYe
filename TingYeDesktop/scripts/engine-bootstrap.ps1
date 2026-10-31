# ASCII source keeps this helper readable by Windows PowerShell 5.1 without a BOM.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$engineUtf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $engineUtf8
$OutputEncoding = $engineUtf8
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

function Write-EngineStatus([string]$message) {
    [Console]::Out.WriteLine((ConvertTo-Json -Compress @{message=$message}))
}

function ConvertTo-ProcessArgument([string]$value) {
    # Windows CRT argument quoting, including embedded quotes and trailing slashes.
    $escaped = [regex]::Replace($value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-EnginePython([string]$executable, [string[]]$arguments, [switch]$ReportProgress) {
    # Native stderr is data, not a PowerShell error. Check ExitCode explicitly.
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $executable
    $info.Arguments = (($arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = New-Object Text.UTF8Encoding($false)
    $info.StandardErrorEncoding = New-Object Text.UTF8Encoding($false)
    $info.EnvironmentVariables['PYTHONUTF8'] = '1'
    $info.EnvironmentVariables['PYTHONIOENCODING'] = 'utf-8'
    $info.EnvironmentVariables['PYTHONUNBUFFERED'] = '1'
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        # Read both pipes concurrently. ReadToEnd hides pip's progress until exit.
        $readers = @($process.StandardOutput, $process.StandardError)
        $pending = @($readers[0].ReadLineAsync(), $readers[1].ReadLineAsync())
        $tails = @('', '')
        $lastOutput = [DateTime]::UtcNow
        $lastReport = [DateTime]::MinValue
        while ($null -ne $pending[0] -or $null -ne $pending[1]) {
            for ($i = 0; $i -lt 2; $i++) {
                if ($null -ne $pending[$i] -and $pending[$i].IsCompleted) {
                    $line = $pending[$i].GetAwaiter().GetResult()
                    if ($null -eq $line) { $pending[$i] = $null; continue }
                    $tails[$i] = $tails[$i] + $line + "`n"
                    if ($tails[$i].Length -gt 12000) { $tails[$i] = $tails[$i].Substring($tails[$i].Length - 12000) }
                    $lastOutput = [DateTime]::UtcNow
                    if ($ReportProgress -and $line.Trim()) {
                        # Raw byte progress is frequent; package/stage changes are immediate.
                        if ($line -notmatch '^Progress ' -or ($lastOutput - $lastReport).TotalMilliseconds -ge 400) {
                            [Console]::Out.WriteLine((ConvertTo-Json -Compress @{detail=$line.Trim();waitingSeconds=0}))
                            $lastReport = $lastOutput
                        }
                    }
                    $pending[$i] = $readers[$i].ReadLineAsync()
                }
            }
            if ($ReportProgress -and ([DateTime]::UtcNow - $lastOutput).TotalSeconds -ge 15 -and ([DateTime]::UtcNow - $lastReport).TotalSeconds -ge 15) {
                [Console]::Out.WriteLine((ConvertTo-Json -Compress @{waitingSeconds=[int]([DateTime]::UtcNow - $lastOutput).TotalSeconds}))
                $lastReport = [DateTime]::UtcNow
            }
            Start-Sleep -Milliseconds 20
        }
        $process.WaitForExit()
        return [pscustomobject]@{ExitCode=$process.ExitCode;Output=$tails[0];Error=$tails[1]}
    } finally { $process.Dispose() }
}

function Assert-EngineSuccess($result, [string]$stage) {
    if ($result.ExitCode -ne 0) {
        $detail = ($result.Error + "`n" + $result.Output).Trim()
        if ($detail.Length -gt 1800) { $detail = $detail.Substring($detail.Length - 1800) }
        throw "$stage (exit $($result.ExitCode))`n$detail"
    }
}

function Initialize-EnginePython([string]$runtimeRoot) {
    $folder = [IO.Path]::GetFullPath($runtimeRoot)
    [void][IO.Directory]::CreateDirectory($folder)
    $python = Join-Path $folder 'python.exe'
    $venv = Join-Path $folder 'Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $python) -and (Test-Path -LiteralPath $venv)) { $python = $venv }
    if (-not (Test-Path -LiteralPath $python)) {
        Write-EngineStatus 'Downloading Python runtime'
        $archive = Join-Path $folder 'python-runtime.zip'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip' -OutFile $archive -TimeoutSec 120
        Expand-Archive -LiteralPath $archive -DestinationPath $folder -Force
        Remove-Item -LiteralPath $archive -Force
    }
    if ($python -eq (Join-Path $folder 'python.exe')) {
        # Repair the import path on retries after an interrupted first installation.
        [void][IO.Directory]::CreateDirectory((Join-Path $folder 'Lib/site-packages'))
        [IO.File]::WriteAllText((Join-Path $folder 'python312._pth'), "python312.zip`n.`nLib`nLib/site-packages`nimport site`n", [Text.Encoding]::ASCII)
    }
    $health = Invoke-EnginePython $python @('-c', 'import ssl, zipfile, sys; print(sys.version)')
    Assert-EngineSuccess $health 'Python runtime validation failed'
    $pip = Invoke-EnginePython $python @('-m', 'pip', '--version')
    if ($pip.ExitCode -ne 0) {
        Write-EngineStatus 'Installing pip'
        $bootstrap = Join-Path $folder 'get-pip.py'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://bootstrap.pypa.io/get-pip.py' -OutFile $bootstrap -TimeoutSec 120
        $installed = Invoke-EnginePython $python @($bootstrap, '--disable-pip-version-check', '--no-cache-dir', '--timeout', '30', '--retries', '2', '--progress-bar', 'raw') -ReportProgress
        Assert-EngineSuccess $installed 'pip installation failed'
        Remove-Item -LiteralPath $bootstrap -Force
        $pip = Invoke-EnginePython $python @('-m', 'pip', '--version')
        Assert-EngineSuccess $pip 'pip validation failed'
    }
    return $python
}
