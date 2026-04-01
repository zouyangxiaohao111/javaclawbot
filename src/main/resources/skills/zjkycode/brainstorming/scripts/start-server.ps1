#!/usr/bin/env pwsh
# Start the brainstorm server and output connection info
# Usage:
#   .\start-server.ps1 [--project-dir <path>] [--host <bind-host>] [--url-host <display-host>] [--foreground] [--background]
#
# Behavior:
# - Starts server on a random high port
# - Outputs JSON with URL
# - Each session gets its own directory to avoid conflicts
#
# Notes:
# - This script is intended for Windows PowerShell / PowerShell on Windows
# - Keep start-server.sh for Linux/macOS/Git Bash

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Write-JsonAndExit {
    param(
        [hashtable]$Object,
        [int]$ExitCode = 1
    )
    $json = $Object | ConvertTo-Json -Compress
    Write-Output $json
    exit $ExitCode
}

function Get-ParentProcessId {
    param([int]$ProcessId)

    try {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
        if ($null -ne $proc) {
            return [int]$proc.ParentProcessId
        }
    } catch {
    }

    return $null
}

# -----------------------------
# Parse arguments
# -----------------------------
$ProjectDir = ""
$Foreground = $false
$ForceBackground = $false
$BindHost = "127.0.0.1"
$UrlHost = ""

$argv = $args
for ($i = 0; $i -lt $argv.Count; $i++) {
    switch ($argv[$i]) {
        '--project-dir' {
            if ($i + 1 -ge $argv.Count) {
                Write-JsonAndExit @{ error = 'Missing value for --project-dir' }
            }
            $ProjectDir = $argv[++$i]
        }
        '--host' {
            if ($i + 1 -ge $argv.Count) {
                Write-JsonAndExit @{ error = 'Missing value for --host' }
            }
            $BindHost = $argv[++$i]
        }
        '--url-host' {
            if ($i + 1 -ge $argv.Count) {
                Write-JsonAndExit @{ error = 'Missing value for --url-host' }
            }
            $UrlHost = $argv[++$i]
        }
        '--foreground' { $Foreground = $true }
        '--no-daemon'  { $Foreground = $true }
        '--background' { $ForceBackground = $true }
        '--daemon'     { $ForceBackground = $true }
        default {
            Write-JsonAndExit @{ error = "Unknown argument: $($argv[$i])" }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($UrlHost)) {
    if ($BindHost -eq '127.0.0.1' -or $BindHost -eq 'localhost') {
        $UrlHost = 'localhost'
    } else {
        $UrlHost = $BindHost
    }
}

# Some environments reap detached/background processes
if ($env:CODEX_CI -and -not $Foreground -and -not $ForceBackground) {
    $Foreground = $true
}

# -----------------------------
# Generate unique session directory
# -----------------------------
$SessionId = "$PID-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"

if (-not [string]::IsNullOrWhiteSpace($ProjectDir)) {
    $SessionDir = Join-Path $ProjectDir ".zjkycode\brainstorm\$SessionId"
} else {
    $TempRoot = $env:TEMP
    if ([string]::IsNullOrWhiteSpace($TempRoot)) {
        $TempRoot = [System.IO.Path]::GetTempPath()
    }
    $SessionDir = Join-Path $TempRoot "brainstorm-$SessionId"
}

$StateDir = Join-Path $SessionDir "state"
$PidFile  = Join-Path $StateDir "server.pid"
$LogFile  = Join-Path $StateDir "server.log"

New-Item -ItemType Directory -Force -Path (Join-Path $SessionDir "content") | Out-Null
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

# Kill any existing server referenced by this PID file
if (Test-Path $PidFile) {
    try {
        $oldPidRaw = (Get-Content $PidFile -ErrorAction Stop | Select-Object -First 1).Trim()
        if ($oldPidRaw -match '^\d+$') {
            Stop-Process -Id ([int]$oldPidRaw) -Force -ErrorAction SilentlyContinue
        }
    } catch {
    }

    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

Push-Location $ScriptDir
try {
    # Resolve the harness PID (grandparent of this script)
    $parentPid = Get-ParentProcessId -ProcessId $PID
    $grandParentPid = $null
    if ($null -ne $parentPid) {
        $grandParentPid = Get-ParentProcessId -ProcessId $parentPid
    }

    if ($null -ne $grandParentPid -and $grandParentPid -ne 1) {
        $OwnerPid = $grandParentPid
    } elseif ($null -ne $parentPid) {
        $OwnerPid = $parentPid
    } else {
        $OwnerPid = $PID
    }

    # -----------------------------------------
    # Foreground mode
    # -----------------------------------------
    if ($Foreground) {
        Set-Content -Path $PidFile -Value $PID -Encoding Ascii

        $envBackup = @{
            BRAINSTORM_DIR       = [Environment]::GetEnvironmentVariable('BRAINSTORM_DIR', 'Process')
            BRAINSTORM_HOST      = [Environment]::GetEnvironmentVariable('BRAINSTORM_HOST', 'Process')
            BRAINSTORM_URL_HOST  = [Environment]::GetEnvironmentVariable('BRAINSTORM_URL_HOST', 'Process')
            BRAINSTORM_OWNER_PID = [Environment]::GetEnvironmentVariable('BRAINSTORM_OWNER_PID', 'Process')
        }

        [Environment]::SetEnvironmentVariable('BRAINSTORM_DIR',       $SessionDir, 'Process')
        [Environment]::SetEnvironmentVariable('BRAINSTORM_HOST',      $BindHost,   'Process')
        [Environment]::SetEnvironmentVariable('BRAINSTORM_URL_HOST',  $UrlHost,    'Process')
        [Environment]::SetEnvironmentVariable('BRAINSTORM_OWNER_PID', "$OwnerPid", 'Process')

        try {
            & node "server.cjs"
            exit $LASTEXITCODE
        } finally {
            foreach ($k in $envBackup.Keys) {
                [Environment]::SetEnvironmentVariable($k, $envBackup[$k], 'Process')
            }
        }
    }

    # -----------------------------------------
    # Background mode
    # Use a generated cmd launcher so child env vars + file redirection work reliably
    # -----------------------------------------
    $LauncherFile = Join-Path $StateDir "run-server.cmd"

    $launcherContent = @"
@echo off
setlocal
set "BRAINSTORM_DIR=$SessionDir"
set "BRAINSTORM_HOST=$BindHost"
set "BRAINSTORM_URL_HOST=$UrlHost"
set "BRAINSTORM_OWNER_PID=$OwnerPid"
cd /d "$ScriptDir"
node server.cjs >> "$LogFile" 2>&1
"@

    Set-Content -Path $LauncherFile -Value $launcherContent -Encoding Ascii

    $proc = Start-Process `
        -FilePath "cmd.exe" `
        -ArgumentList "/d", "/c", "`"$LauncherFile`"" `
        -WindowStyle Hidden `
        -PassThru

    Set-Content -Path $PidFile -Value $proc.Id -Encoding Ascii

    # Wait for server-started message
    for ($i = 0; $i -lt 50; $i++) {
        if (Test-Path $LogFile) {
            $matchedLine = Get-Content $LogFile -Tail 50 -ErrorAction SilentlyContinue |
                Select-String "server-started" |
                Select-Object -First 1

            if ($null -ne $matchedLine) {
                # Verify server is still alive after a short window
                $alive = $true
                for ($j = 0; $j -lt 20; $j++) {
                    $proc.Refresh()
                    if ($proc.HasExited) {
                        $alive = $false
                        break
                    }
                    Start-Sleep -Milliseconds 100
                }

                if (-not $alive) {
                    $retryCmd = "$ScriptDir\start-server.ps1"
                    if (-not [string]::IsNullOrWhiteSpace($ProjectDir)) {
                        $retryCmd += " --project-dir `"$ProjectDir`""
                    }
                    $retryCmd += " --host $BindHost --url-host $UrlHost --foreground"

                    Write-JsonAndExit @{
                        error = "Server started but was killed. Retry in a persistent terminal with: $retryCmd"
                    }
                }

                Write-Output $matchedLine.Line
                exit 0
            }
        }

        Start-Sleep -Milliseconds 100
    }

    Write-JsonAndExit @{ error = "Server failed to start within 5 seconds" }
}
finally {
    Pop-Location
}