<#
.SYNOPSIS
Start SkillHub in the shared local-dev Windows Terminal window.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectId = 'skillhub'
$projectTitle = 'SkillHub'
$windowName = 'local-dev'
$stateDirectory = Join-Path $env:LOCALAPPDATA 'LocalDevTerminal'
$statePath = Join-Path $stateDirectory 'tabs.json'

function Get-LiveDevTabs {
    param([object[]]$Tabs)

    foreach ($tab in $Tabs) {
        $registrationPath = Join-Path $stateDirectory ($tab.Token + '.json')
        if (-not (Test-Path -LiteralPath $registrationPath)) {
            continue
        }

        try {
            $registration = Get-Content -LiteralPath $registrationPath -Raw | ConvertFrom-Json
            $process = Get-Process -Id $registration.ProcessId -ErrorAction Stop
            if ($process.StartTime.ToUniversalTime().Ticks -eq $registration.StartTimeTicks) {
                $tab
            }
        } catch {
            # A closed tab or a partially written registration is no longer live.
        }
    }
}

function Invoke-DevTerminal {
    param([string[]]$TerminalArguments)

    & wt.exe @TerminalArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Windows Terminal exited with code $LASTEXITCODE."
    }
}

$launchMutex = $null
$lockHeld = $false
try {
    Get-Command wt.exe -CommandType Application -ErrorAction Stop | Out-Null
    $shell = (Get-Command powershell.exe -CommandType Application -ErrorAction Stop).Source
    # Share the lock and tab registry with the reference launchers.
    $launchMutex = [System.Threading.Mutex]::new($false, 'Local\AIGatewayCasdoorDevLauncher')
    try {
        try {
            $lockHeld = $launchMutex.WaitOne(30000)
        } catch [System.Threading.AbandonedMutexException] {
            $lockHeld = $true
        }
        if (-not $lockHeld) {
            throw 'Another development launcher is still busy. Try again shortly.'
        }

        New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
        $tabs = [System.Collections.Generic.List[object]]::new()
        if (Test-Path -LiteralPath $statePath) {
            $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            foreach ($tab in @(Get-LiveDevTabs -Tabs @($state.Tabs))) {
                $tabs.Add($tab)
            }
        }

        # Build the required modules, then run with the root's local properties file.
        $backendCommand = @'
& .\mvnw.cmd -pl skillhub-app -am clean package '-DskipTests'
if ($LASTEXITCODE -ne 0) { throw "Backend build failed with code $LASTEXITCODE." }
$appJar = @(Get-ChildItem -LiteralPath 'skillhub-app/target' -Filter 'skillhub-app-*.jar' -File)
if ($appJar.Count -ne 1) { throw 'Expected one packaged skillhub-app jar under server/skillhub-app/target.' }
$java = if ($env:JAVA_BIN) { $env:JAVA_BIN } else { 'java.exe' }
& $java -jar $appJar[0].FullName '--spring.profiles.active=local' '--spring.config.additional-location=file:../.env.local.properties'
if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE." }
'@
        $frontendCommand = @'
$modulesPath = 'node_modules/.modules.yaml'
if (-not (Test-Path -LiteralPath $modulesPath) -or
    (Get-Item -LiteralPath 'pnpm-lock.yaml').LastWriteTimeUtc -gt (Get-Item -LiteralPath $modulesPath).LastWriteTimeUtc) {
    & pnpm.cmd install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw "Frontend dependency installation failed with code $LASTEXITCODE." }
}
$webHost = if ($env:DEV_WEB_HOST) { $env:DEV_WEB_HOST } else { '127.0.0.1' }
& pnpm.cmd exec vite --host $webHost --strictPort
if ($LASTEXITCODE -ne 0) { throw "Frontend exited with code $LASTEXITCODE." }
'@
        $services = @(
            @{ Id = "$projectId-backend"; Title = "$projectTitle Backend"; Directory = (Join-Path $PSScriptRoot 'server'); Command = $backendCommand; Executable = 'java.exe' },
            @{ Id = "$projectId-frontend"; Title = "$projectTitle Frontend"; Directory = (Join-Path $PSScriptRoot 'web'); Command = $frontendCommand; Executable = 'pnpm.cmd' }
        )
        $pendingServices = @($services | Where-Object { $_.Id -notin @($tabs | ForEach-Object { $_.Id }) })
        foreach ($service in $pendingServices) {
            Get-Command $service.Executable -CommandType Application -ErrorAction Stop | Out-Null
            if (-not (Test-Path -LiteralPath $service.Directory -PathType Container)) {
                throw "Service directory missing: $($service.Directory)"
            }
        }

        $terminalArgs = [System.Collections.Generic.List[string]]::new()
        $terminalArgs.AddRange([string[]]@('-w', $windowName))
        $pendingTabs = [System.Collections.Generic.List[object]]::new()
        foreach ($service in $pendingServices) {
            # Always append, including when Terminal's newTabPosition is afterCurrentTab.
            if ($tabs.Count -gt 0) {
                $terminalArgs.AddRange([string[]]@('focus-tab', '-t', [string]($tabs.Count - 1), ';'))
            }

            $tab = [pscustomobject]@{ Id = $service.Id; Token = [guid]::NewGuid().ToString('N') }
            $registrationPath = (Join-Path $stateDirectory ($tab.Token + '.json')).Replace("'", "''")
            # Track the shell until its tab closes, including an idle tab showing an error.
            # Removing it earlier would make the saved tab indices disagree with Terminal.
            $command = @"
`$ErrorActionPreference = 'Stop'
`$process = Get-Process -Id `$PID
@{ ProcessId = `$PID; StartTimeTicks = `$process.StartTime.ToUniversalTime().Ticks } |
    ConvertTo-Json | Set-Content -LiteralPath '$registrationPath' -Encoding UTF8
try {
$($service.Command)
} catch {
    Write-Host `$_ -ForegroundColor Red
}
Write-Host 'Close this tab and rerun start-dev.ps1 to restart this service.'
"@
            # Encoding keeps quotes and command separators intact through wt.exe.
            $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
            $terminalArgs.AddRange([string[]]@(
                'new-tab', '--title', $service.Title, '--suppressApplicationTitle',
                '-d', $service.Directory,
                $shell, '-NoLogo', '-NoProfile', '-NoExit', '-EncodedCommand', $encodedCommand,
                ';'
            ))
            $tabs.Add($tab)
            $pendingTabs.Add($tab)
        }

        if ($pendingTabs.Count -gt 0) {
            # Persist pending tabs so a later launch can recover after an interrupted launch.
            @{ Tabs = @($tabs.ToArray()) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding UTF8
            $terminalArgs.RemoveAt($terminalArgs.Count - 1)
            Invoke-DevTerminal -TerminalArguments $terminalArgs.ToArray()

            $deadline = [DateTime]::UtcNow.AddSeconds(15)
            while (@(Get-LiveDevTabs -Tabs $pendingTabs.ToArray()).Count -ne $pendingTabs.Count) {
                if ([DateTime]::UtcNow -ge $deadline) {
                    throw 'Timed out waiting for development tabs to open. Run this script again to retry.'
                }
                Start-Sleep -Milliseconds 100
            }
        }

        # Keep other projects in their current order; place SkillHub's two tabs last.
        $desiredTabs = @($tabs | Where-Object { $_.Id -notin @("$projectId-backend", "$projectId-frontend") }) +
            @($tabs | Where-Object { $_.Id -eq "$projectId-backend" }) +
            @($tabs | Where-Object { $_.Id -eq "$projectId-frontend" })
        $currentIds = @($tabs | ForEach-Object { $_.Id }) -join ','
        $desiredIds = @($desiredTabs | ForEach-Object { $_.Id }) -join ','
        if ($currentIds -ne $desiredIds) {
            $terminalArgs.Clear()
            $terminalArgs.AddRange([string[]]@('-w', $windowName))
            foreach ($tab in $desiredTabs) {
                $index = $tabs.IndexOf($tab)
                if ($index -eq $tabs.Count - 1) {
                    continue
                }

                # Temporarily attach to the last tab, then detach into a new last tab.
                # This preserves the running shell and works with either newTabPosition.
                $terminalArgs.AddRange([string[]]@(
                    'focus-tab', '-t', [string]$index, ';',
                    'move-pane', '-t', [string]($tabs.Count - 1), ';',
                    'move-pane', '-t', [string]$tabs.Count, ';'
                ))
                $tabs.RemoveAt($index)
                $tabs.Add($tab)
            }
            $terminalArgs.AddRange([string[]]@('focus-tab', '-t', '0'))
            Invoke-DevTerminal -TerminalArguments $terminalArgs.ToArray()
        } elseif ($pendingTabs.Count -eq 0 -and $tabs.Count -gt 0) {
            Invoke-DevTerminal -TerminalArguments @('-w', $windowName, 'focus-tab', '-t', '0')
        }

        @{ Tabs = @($tabs.ToArray()) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding UTF8
    } finally {
        if ($lockHeld) {
            $launchMutex.ReleaseMutex()
        }
    }
} catch {
    Write-Host $_ -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
} finally {
    if ($null -ne $launchMutex) {
        $launchMutex.Dispose()
    }
}
