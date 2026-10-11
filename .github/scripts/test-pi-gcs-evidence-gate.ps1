param(
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$Revision
)
$ErrorActionPreference = 'Stop'
$verifier = Join-Path $PSScriptRoot 'verify-pi-gcs-evidence.ps1'
$source = (Resolve-Path -LiteralPath $Root).Path
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("pi-gcs-gate-" + [guid]::NewGuid().ToString('N'))
New-Item -Path $tempRoot -ItemType Directory -Force | Out-Null

function Invoke-Gate([string]$CaseRoot, [bool]$ExpectedSuccess, [string]$Label) {
    & pwsh -NoProfile -NonInteractive -File $verifier -Root $CaseRoot -RunId $RunId -Revision $Revision -CycleCount 20 *> $null
    $exit = $LASTEXITCODE
    if (($exit -eq 0) -ne $ExpectedSuccess) {
        throw "Unexpected gate verdict for $Label; expected success=$ExpectedSuccess; exit=$exit"
    }
    Write-Host "PASS: $Label gate exit=$exit"
}

try {
    # Baseline validates the generated artifact before modifying copies.
    Invoke-Gate $source $true 'intact'
    $mutations = @('trace-hash', 'run-id', 'aggregate-result', 'missing-cycle')
    foreach ($mutation in $mutations) {
        $caseRoot = Join-Path $tempRoot $mutation
        Copy-Item -LiteralPath $source -Destination $caseRoot -Recurse -Force
        switch ($mutation) {
            'trace-hash' {
                Add-Content -LiteralPath (Join-Path $caseRoot 'cycle-01/trace.jsonl') -Value '{"tampered":true}'
            }
            'run-id' {
                $manifestPath = Join-Path $caseRoot 'cycle-01/provenance.json'
                $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
                $manifest.runId = 'mismatched-run'
                $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath
            }
            'aggregate-result' {
                $aggregatePath = Join-Path $caseRoot 'aggregate-summary.json'
                $aggregate = Get-Content -LiteralPath $aggregatePath -Raw | ConvertFrom-Json
                $aggregate.result = 'FAIL'
                $aggregate | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $aggregatePath
            }
            'missing-cycle' {
                Remove-Item -LiteralPath (Join-Path $caseRoot 'cycle-20') -Recurse -Force
            }
        }
        Invoke-Gate $caseRoot $false $mutation
    }
    Write-Host 'PASS: 4 independent mutation tests rejected corrupted virtual GCS evidence.'
    # The last expected-negative child process exits 1. Clear its status so
    # GitHub Actions treats the successful harness as success.
    $global:LASTEXITCODE = 0
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# A successful mutation harness must return success irrespective of the
# expected nonzero exit code from the final isolated verifier invocation.
exit 0
