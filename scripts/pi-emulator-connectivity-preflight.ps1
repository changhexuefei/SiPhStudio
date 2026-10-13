param(
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$Port,
    [string]$OutputPath = './pi-emulator-connectivity-preflight.json'
)
$ErrorActionPreference = 'Stop'
# Network reachability only. Never send a PI GCS command or issue motion.
$tcp = New-Object System.Net.Sockets.TcpClient
$connected = $false
$errorType = $null
try {
    $async = $tcp.BeginConnect($HostName, $Port, $null, $null)
    try {
        if (-not $async.AsyncWaitHandle.WaitOne(3000)) {
            throw [System.TimeoutException]::new('TCP connection timed out after 3000 ms')
        }
        $tcp.EndConnect($async)
        $connected = $true
    } finally {
        $async.AsyncWaitHandle.Close()
    }
} catch {
    $errorType = $_.Exception.GetType().Name
} finally {
    $tcp.Dispose()
}
# Never persist HostName, IP address or raw communication payload in artifact.
$report = [ordered]@{
    schemaVersion = 1
    evidenceCategory = 'CONNECTIVITY_PREFLIGHT_ONLY'
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    tcpReachable = $connected
    errorType = $errorType
    gcsCommandsSent = 0
    officialEmulatorProtocolAcceptance = 'NOT_RUN'
    physicalMotion = 'NOT_VALIDATED'
}
$report | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath $OutputPath
if (-not $connected) { throw 'EMULATOR_TCP_ENDPOINT_NOT_REACHABLE: verify vendor-configured host and port' }
Write-Output 'TCP_ENDPOINT_REACHABLE_ONLY: no GCS identity, position or official protocol acceptance validated'
