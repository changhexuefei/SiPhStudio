param(
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$Revision,
    [int]$CycleCount = 20
)
$ErrorActionPreference = 'Stop'
if ($CycleCount -lt 1) { throw 'CycleCount must be positive' }
if (-not (Test-Path -LiteralPath $Root -PathType Container)) { throw "Missing evidence root: $Root" }
$rootDir = (Resolve-Path -LiteralPath $Root).Path
$aggregatePath = Join-Path $rootDir 'aggregate-summary.json'
if (-not (Test-Path -LiteralPath $aggregatePath -PathType Leaf)) { throw 'Missing aggregate-summary.json' }
$aggregate = Get-Content -LiteralPath $aggregatePath -Raw | ConvertFrom-Json
if ($aggregate.schemaVersion -ne 1 -or $aggregate.source -cne 'VIRTUAL_ONLY' -or
    $aggregate.scenario -cne 'recovery-soak' -or $aggregate.result -cne 'PASS' -or
    $aggregate.expectedCycles -ne $CycleCount -or $aggregate.passedCycles -ne $CycleCount -or
    $aggregate.failedCycles -ne 0 -or $aggregate.failures.Count -ne 0 -or
    $aggregate.runId -cne $RunId -or $aggregate.revision -cne $Revision) {
    throw 'Aggregate evidence verdict does not match the current CI run/revision or expected pass counts'
}
$expectedDirs = @(1..$CycleCount | ForEach-Object { 'cycle-{0:D2}' -f $_ })
$actualEntries = @(Get-ChildItem -LiteralPath $rootDir -Force)
foreach ($entry in $actualEntries) {
    if ($entry.Name -ne 'aggregate-summary.json' -and $entry.Name -cnotin $expectedDirs) {
        throw "Unexpected evidence root entry: $($entry.Name)"
    }
}
foreach ($n in 1..$CycleCount) {
    $name = 'cycle-{0:D2}' -f $n
    $dir = Join-Path $rootDir $name
    if (-not (Test-Path -LiteralPath $dir -PathType Container)) { throw "Missing cycle directory: $name" }
    $expected = @('summary.json','trace.jsonl','provenance.json')
    $files = @(Get-ChildItem -LiteralPath $dir -Force)
    if ($files.Count -ne 3 -or @($files | Where-Object { $_.Name -cnotin $expected -or $_.PSIsContainer }).Count -gt 0) {
        throw "Unexpected or missing files in $name"
    }
    $summaryPath = Join-Path $dir 'summary.json'
    $tracePath = Join-Path $dir 'trace.jsonl'
    $manifestPath = Join-Path $dir 'provenance.json'
    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $lines = @(Get-Content -LiteralPath $tracePath | Where-Object { $_.Trim().Length -gt 0 })
    if ($summary.schemaVersion -ne 1 -or $summary.source -cne 'VIRTUAL_ONLY' -or
        $summary.scenario -cne "recovery-cycle-$n" -or $summary.result -cne 'PASS' -or
        $summary.failures.Count -ne 0 -or $summary.eventCount -ne $lines.Count) {
        throw "Invalid cycle summary: $name"
    }
    if ($manifest.schemaVersion -ne 1 -or $manifest.source -cne 'VIRTUAL_ONLY' -or
        $manifest.runId -cne $RunId -or $manifest.revision -cne $Revision -or
        $manifest.summarySha256 -cne (Get-FileHash -LiteralPath $summaryPath -Algorithm SHA256).Hash.ToLowerInvariant() -or
        $manifest.traceSha256 -cne (Get-FileHash -LiteralPath $tracePath -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw "SHA-256 provenance mismatch: $name"
    }
    $events = @($lines | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($events | Where-Object { $_.schemaVersion -ne 1 }).Count -gt 0 -or
        @($events | Where-Object { $_.type -ceq 'CONNECTED' }).Count -ne 2 -or
        @($events | Where-Object { $_.type -ceq 'DISCONNECTED' }).Count -ne 2 -or
        @($events | Where-Object { $_.outcome -ceq 'FAULT_TIMEOUT' }).Count -ne 1 -or
        @($events | Where-Object { $_.outcome -ceq 'REPLIED' }).Count -ne 2 -or
        $events[-1].type -cne 'SERVER_CLOSED') {
        throw "Invalid trace event counts: $name"
    }
}
Write-Host "PASS: $CycleCount simulator-only recovery cycles match the current CI run, revision and SHA-256 manifests."
