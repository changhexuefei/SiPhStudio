$ErrorActionPreference = 'Stop'
$preflight = Join-Path (Get-Location) 'scripts/pi-emulator-connectivity-preflight.ps1'
$folder = Join-Path $env:RUNNER_TEMP 'pi-connectivity-smoke'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$accepted = $null
try {
    $pending = $listener.BeginAcceptTcpClient($null, $null)
    $positive = Join-Path $folder 'reachable.json'
    & $preflight -HostName '127.0.0.1' -Port $port -OutputPath $positive
    if (-not $pending.AsyncWaitHandle.WaitOne(5000)) { throw 'Loopback connection was never accepted' }
    $accepted = $listener.EndAcceptTcpClient($pending)
    Start-Sleep -Milliseconds 150
    if ($accepted.Available -ne 0) { throw 'Connectivity preflight must transmit zero GCS bytes' }
    $result = Get-Content $positive -Raw | ConvertFrom-Json
    if (-not $result.tcpReachable -or $result.gcsCommandsSent -ne 0 -or
        $result.officialEmulatorProtocolAcceptance -ne 'NOT_RUN' -or
        $result.physicalMotion -ne 'NOT_VALIDATED' -or
        $result.evidenceCategory -ne 'CONNECTIVITY_PREFLIGHT_ONLY') {
        throw 'Reachable preflight wrongly implies protocol or motion validation'
    }
    if ((Get-Content $positive -Raw) -match [regex]::Escape('127.0.0.1')) {
        throw 'Preflight evidence must not include endpoint identity'
    }
} finally {
    if ($null -ne $accepted) { $accepted.Dispose() }
    $listener.Stop()
}
$refused = Join-Path $folder 'refused.json'
$rejected = $false
try { & $preflight -HostName '127.0.0.1' -Port $port -OutputPath $refused }
catch { $rejected = $_.Exception.Message -like '*EMULATOR_TCP_ENDPOINT_NOT_REACHABLE*' }
if (-not $rejected) { throw 'Closed loopback TCP listener was incorrectly accepted' }
$report = Get-Content $refused -Raw | ConvertFrom-Json
if ($report.tcpReachable -or $report.gcsCommandsSent -ne 0 -or
    $report.officialEmulatorProtocolAcceptance -ne 'NOT_RUN') {
    throw 'Rejected endpoint report incorrectly validates connectivity or protocol'
}
Write-Output 'CONNECTIVITY_PREFLIGHT_CONTRACT_PASS: reachable socket, refused socket, zero GCS bytes, no vendor claims'
