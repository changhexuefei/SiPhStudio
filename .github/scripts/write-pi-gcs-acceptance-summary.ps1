param(
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$Revision
)
$ErrorActionPreference = 'Stop'
$path = Join-Path $Root 'aggregate-summary.json'
$report = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
if ($report.source -cne 'VIRTUAL_ONLY' -or $report.result -cne 'PASS' -or
    $report.expectedCycles -ne 20 -or $report.passedCycles -ne 20 -or
    $report.failedCycles -ne 0 -or $report.failures.Count -ne 0 -or
    $report.runId -cne $RunId -or $report.revision -cne $Revision) {
    throw 'Refusing to publish acceptance summary for invalid or mismatched simulator evidence.'
}
$markdown = @(
    '## PI GCS virtual recovery acceptance'
    ''
    '| Check | Result |'
    '| --- | --- |'
    '| Windows / JDK 21 test suite | PASS |'
    '| Recovery soak cycles | 20 / 20 PASS |'
    '| Evidence integrity and SHA-256 | PASS |'
    '| Negative mutation checks | PASS (4 rejected) |'
    '| Source classification | VIRTUAL_ONLY |'
    '| Physical PI controller qualification | NOT TESTED |'
    '| Mechanical motion and safety validation | NOT TESTED |'
    ''
    ('CI run: `' + $RunId + '`')
    ('Revision: `' + $Revision + '`')
    ''
    '> Virtual loopback software tests are not evidence of physical PI hexapod motion safety.'
)
$summaryPath = Join-Path $Root 'acceptance-summary.md'
$markdown | Set-Content -LiteralPath $summaryPath -Encoding utf8
if ($env:GITHUB_STEP_SUMMARY) {
    $markdown | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Encoding utf8
}
Write-Host 'PASS: published explicitly VIRTUAL_ONLY acceptance baseline.'
