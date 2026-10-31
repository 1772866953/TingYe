param([Parameter(Mandatory=$true)][string]$RuntimeRoot,[Parameter(Mandatory=$true)][string]$Requirements)
. (Join-Path $PSScriptRoot 'engine-bootstrap.ps1')
try {
    $runtimePython = Initialize-EnginePython $RuntimeRoot
    Write-EngineStatus 'Installing GPU inference components'
    $options = @('-m','pip','install','--disable-pip-version-check','--no-cache-dir','--timeout','30','--retries','2','--resume-retries','2','--progress-bar','raw')
    $gpu = Invoke-EnginePython $runtimePython ($options + @('torch==2.9.1','torchaudio==2.9.1','--index-url','https://download.pytorch.org/whl/cu128')) -ReportProgress
    Assert-EngineSuccess $gpu 'GPU component installation failed'
    Write-EngineStatus 'Installing speech recognition components'
    Write-EngineStatus 'Using Tsinghua Python package mirror'
    $recognition = Invoke-EnginePython $runtimePython ($options + @('--prefer-binary','--index-url','https://pypi.tuna.tsinghua.edu.cn/simple','-r',$Requirements)) -ReportProgress
    if ($recognition.ExitCode -ne 0) {
        Write-EngineStatus 'Retrying with official Python package index'
        $recognition = Invoke-EnginePython $runtimePython ($options + @('--prefer-binary','--index-url','https://pypi.org/simple','-r',$Requirements)) -ReportProgress
    }
    Assert-EngineSuccess $recognition 'Speech component installation failed'
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
