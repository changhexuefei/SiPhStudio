param(
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$Revision
)
$ErrorActionPreference = 'Stop'
$generator = Join-Path $PSScriptRoot 'write-pi-gcs-acceptance-summary.ps1'
$source = (Resolve-Path -LiteralPath $Root).Path
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('pi-gcs-report-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $tempRoot -ItemType Directory | Out-Null

function Invoke-Report([string]$Folder, [bool]$ShouldPass, [string]$Case) {
    $old = $env:GITHUB_STEP_SUMMARY
    $env:GITHUB_STEP_SUMMARY = Join-Path $Folder 'job-summary.md'
    try {
        & pwsh -NoProfile -NonInteractive -File $generator -Root $Folder -RunId $RunId -Revision $Revision *> $null
        $exitCode = $LASTEXITCODE
        if (($exitCode -eq 0) -ne $ShouldPass) {
            throw "Unexpected acceptance summary exit code for $Case : $exitCode"
        }
        if ($ShouldPass) {
            $report = Get-Content -LiteralPath (Join-Path $Folder 'acceptance-summary.md') -Raw
            $job = Get-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Raw
            foreach ($token in @('VIRTUAL_ONLY', '20 / 20 PASS', 'Physical PI controller qualification | NOT TESTED', 'Mechanical motion and safety validation | NOT TESTED', $RunId, $Revision)) {
                if (-not $report.Contains($token) -or -not $job.Contains($token)) {
                    throw "Missing acceptance contract token in $Case : $token"
                }
            }
        } elseif (Test-Path -LiteralPath (Join-Path $Folder 'acceptance-summary.md')) {
            throw "Invalid evidence unexpectedly emitted an acceptance report: $Case"
        }
        Write-Host "PASS: acceptance report $Case"
    } finally {
        $env:GITHUB_STEP_SUMMARY = $old
    }
}

try {
    $aggregate = Get-Content -LiteralPath (Join-Path $source 'aggregate-summary.json') -Raw | ConvertFrom-Json
    if ($aggregate.result -cne 'PASS') { throw 'Expected real simulator aggregate to pass' }
    $scenarios = @('intact', 'fail-result', 'wrong-run-id', 'wrong-revision', 'wrong-source', 'incomplete-cycles')
    foreach ($scenario in $scenarios) {
        $folder = Join-Path $tempRoot $scenario
        New-Item -Path $folder -ItemType Directory | Out-Null
        $copy = $aggregate.PSObject.Copy()
        switch ($scenario) {
            'fail-result' { $copy.result = 'FAIL' }
            'wrong-run-id' { $copy.runId = 'another-ci-run' }
            'wrong-revision' { $copy.revision = 'another-commit' }
            'wrong-source' { $copy.source = 'HARDWARE' }
            'incomplete-cycles' { $copy.passedCycles = 19; $copy.failedCycles = 1 }
        }
        $copy | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $folder 'aggregate-summary.json')
        Invoke-Report $folder ($scenario -eq 'intact') $scenario
    }
    Write-Host 'PASS: acceptance summary contract and five negative checks'
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
