param(
    [Parameter(Mandatory=$true)][string]$MsiPath,
    [Parameter(Mandatory=$true)][string]$EvidencePath,
    [Parameter(Mandatory=$true)][string]$OutDir
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$evidence = Get-Content $EvidencePath
$shaLine = @($evidence | Where-Object { $_ -match '^msi_sha256=' })
if ($shaLine.Count -ne 1) { throw 'Expected one MSI SHA-256 in release evidence' }
$expected = ($shaLine[0] -split '=',2)[1].Trim()
if ($expected -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid expected MSI digest' }
$actual = (Get-FileHash -Algorithm SHA256 -Path $MsiPath).Hash
if ($actual -ne $expected) { throw 'Released MSI digest differs from published evidence' }

# This test must run on an ephemeral hosted Windows runner only.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') {
    throw 'Refusing real MSI install/uninstall outside a GitHub-hosted runner'
}
$logInstall = Join-Path $OutDir 'install.log'
$logUninstall = Join-Path $OutDir 'uninstall.log'
$summary = Join-Path $OutDir 'summary.txt'
$wasInstalled = $false
$uninstallPassed = $false
$installPassed = $false
$launchPassed = $false
$program = $null
$process = $null
try {
    $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + (Resolve-Path $MsiPath).Path + '"'), '/qn', '/norestart', '/L*v', ('"' + $logInstall + '"')) -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "Real MSI install failed with exit code $($p.ExitCode)" }
    $wasInstalled = $true
    $installPassed = $true

    # jpackage installs into the application-named directory under Program Files.
    $dirs = @(
        (Join-Path $env:ProgramFiles 'org.jason.siph'),
        (Join-Path ${env:ProgramFiles(x86)} 'org.jason.siph')
    ) | Where-Object { $_ -and (Test-Path $_) }
    $apps = @($dirs | ForEach-Object { Get-ChildItem $_ -File -Recurse -Filter '*.exe' -ErrorAction Stop } |
        Where-Object { $_.Name -match 'siph' })
    if ($apps.Count -ne 1) { throw "Expected one installed SiPhStudio launcher, found $($apps.Count)" }
    $program = $apps[0].FullName
    $process = Start-Process -FilePath $program -WorkingDirectory $apps[0].DirectoryName -PassThru
    Start-Sleep -Seconds 6
    $process.Refresh()
    if ($process.HasExited) { throw "Installed launcher exited during smoke check with code $($process.ExitCode)" }
    $launchPassed = $true
}
finally {
    if ($null -ne $process) {
        $process.Refresh()
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    if ($wasInstalled) {
        $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', ('"' + (Resolve-Path $MsiPath).Path + '"'), '/qn', '/norestart', '/L*v', ('"' + $logUninstall + '"')) -Wait -PassThru
        $uninstallPassed = ($p.ExitCode -eq 0)
        if (-not $uninstallPassed) { Write-Warning "Real MSI uninstall failed with exit code $($p.ExitCode)" }
    }
    @(
      'release=v0.1.0-beta.1'
      "commit=$env:GITHUB_SHA"
      "msi_sha256=$actual"
      "msi_install=$(if ($installPassed) { 'PASS' } else { 'FAIL' })"
      "installed_launcher=$(if ($launchPassed) { 'PASS' } else { 'FAIL' })"
      "msi_uninstall=$(if ($uninstallPassed) { 'PASS' } else { 'FAIL' })"
      'pi_official_emulator=NOT_RUN'
      'physical_controller=NOT_RUN'
      'physical_motion=NOT_VALIDATED'
    ) | Set-Content -Encoding UTF8 -Path $summary
}
if (-not $uninstallPassed) { throw 'Uninstall acceptance failed' }
if (-not $launchPassed) { throw 'Installed launcher acceptance failed' }
# Verify that the application executable was removed; user-data removal is not assumed.
if ($program -and (Test-Path $program)) { throw 'Installed executable remains after uninstall' }
