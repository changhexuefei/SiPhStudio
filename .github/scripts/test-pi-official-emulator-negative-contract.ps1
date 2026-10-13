param(
    [Parameter(Mandatory=$true)][string]$Verifier,
    [Parameter(Mandatory=$true)][string]$Template
)
$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath $Template -Raw | ConvertFrom-Json
$root = Join-Path $env:RUNNER_TEMP 'pi-emulator-negative-contract'
New-Item -ItemType Directory -Force -Path $root | Out-Null
function Assert-Rejected([string]$name, [scriptblock]$change, [string]$expected) {
    $record = Get-Content -LiteralPath $Template -Raw | ConvertFrom-Json
    & $change $record
    $path = Join-Path $root ($name + '.json')
    $record | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -Encoding utf8
    $blocked = $false
    try { & $Verifier -EvidencePath $path -RequirePass | Out-Null }
    catch { $blocked = $_.Exception.Message -like ('*' + $expected + '*') }
    if (-not $blocked) { throw ('UNEXPECTED_ACCEPTANCE_' + $name) }
    Write-Output ('NEGATIVE_CASE_PASS_' + $name)
}
# Each case begins with a fresh template. Even all-PASS claims with placeholder
# metadata must never satisfy strict acceptance.
Assert-Rejected 'not-run' { param($r) } 'OFFICIAL_EMULATOR_ACCEPTANCE_NOT_PASSED'
Assert-Rejected 'fake-pass-placeholder' {
    param($r)
    foreach ($property in $r.checks.PSObject.Properties) { $property.Value = 'PASS' }
} 'OFFICIAL_EMULATOR_REVISION_UNVERIFIED'
Assert-Rejected 'fake-pass-placeholder-version' {
    param($r)
    foreach ($property in $r.checks.PSObject.Properties) { $property.Value = 'PASS' }
    $r.revision = '0123456789abcdef0123456789abcdef01234567'
} 'OFFICIAL_EMULATOR_METADATA_PLACEHOLDER'
Assert-Rejected 'motion-claim' {
    param($r)
    $r.physicalMotion = 'PASS'
} 'OFFICIAL_EMULATOR_MOTION_STATUS_INVALID'
Assert-Rejected 'missing-check' {
    param($r)
    $r.checks.PSObject.Properties.Remove('posSixAxes')
} 'OFFICIAL_EMULATOR_CHECKS_INCOMPLETE'
