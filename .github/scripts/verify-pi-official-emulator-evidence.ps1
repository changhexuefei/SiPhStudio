param(
    [Parameter(Mandatory=$true)][string]$EvidencePath,
    [switch]$RequirePass
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $EvidencePath -PathType Leaf)) { throw 'OFFICIAL_EMULATOR_EVIDENCE_MISSING' }
$data = Get-Content -LiteralPath $EvidencePath -Raw | ConvertFrom-Json
if ($data.schemaVersion -ne 1) { throw 'OFFICIAL_EMULATOR_SCHEMA_UNSUPPORTED' }
if ($data.source -ne 'PIHexapodEmulator') { throw 'OFFICIAL_EMULATOR_SOURCE_UNVERIFIED' }
if ($data.testMode -ne 'READ_ONLY') { throw 'OFFICIAL_EMULATOR_MOTION_FORBIDDEN' }
$requiredMetadata = @('revision','emulatorVersion','controllerModel','gcsVersion','environment','reviewer','evidenceReference')
foreach ($key in $requiredMetadata) {
    if ([string]::IsNullOrWhiteSpace([string]$data.$key)) { throw 'OFFICIAL_EMULATOR_METADATA_INCOMPLETE' }
}
$required = @('connect','idn','posSixAxes','linearUnits','angularUnits','refresh','disconnect','reconnect','timeoutBehavior')
$found = @($data.checks.PSObject.Properties.Name)
if (@($required | Where-Object { $_ -notin $found }).Count -ne 0) { throw 'OFFICIAL_EMULATOR_CHECKS_INCOMPLETE' }
foreach ($key in $required) {
    if ($data.checks.$key -notin @('PASS','FAIL','NOT_RUN')) { throw 'OFFICIAL_EMULATOR_INVALID_STATUS' }
}
if ($RequirePass -and @($required | Where-Object { $data.checks.$_ -ne 'PASS' }).Count -ne 0) {
    throw 'OFFICIAL_EMULATOR_ACCEPTANCE_NOT_PASSED'
}
if ($data.physicalMotion -ne 'NOT_VALIDATED') { throw 'OFFICIAL_EMULATOR_MOTION_STATUS_INVALID' }
Write-Output 'OFFICIAL_EMULATOR_EVIDENCE_SCHEMA_VALID'
if ($RequirePass) { Write-Output 'OFFICIAL_EMULATOR_READ_ONLY_ACCEPTANCE_PASS' }
