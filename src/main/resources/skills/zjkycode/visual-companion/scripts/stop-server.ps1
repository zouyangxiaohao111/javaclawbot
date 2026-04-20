# stop-server.ps1
# Stop the brainstorm server and clean up
# Usage: .\stop-server.ps1 <session_dir>
#
# Kills the server process. Only deletes session directory if it's
# under the system temp directory (ephemeral).
# Persistent directories (.zjkycode/) are kept so mockups can be reviewed later.

param(
    [Parameter(Position = 0)]
    [string]$SessionDir
)

function Write-Json {
    param(
        [hashtable]$Data
    )
    $Data | ConvertTo-Json -Compress
}

function Test-ProcessAlive {
    param(
        [int]$Pid
    )
    return $null -ne (Get-Process -Id $Pid -ErrorAction SilentlyContinue)
}

if ([string]::IsNullOrWhiteSpace($SessionDir)) {
    Write-Output (Write-Json @{ error = "Usage: stop-server.ps1 <session_dir>" })
    exit 1
}

$StateDir = Join-Path $SessionDir "state"
$PidFile  = Join-Path $StateDir "server.pid"
$LogFile  = Join-Path $StateDir "server.log"

if (Test-Path $PidFile) {
    $pidText = (Get-Content $PidFile -Raw).Trim()

    if (-not [int]::TryParse($pidText, [ref]$pid)) {
        Write-Output (Write-Json @{ status = "failed"; error = "invalid pid file" })
        exit 1
    }

    # Try graceful stop first
    try {
        Stop-Process -Id $pid -ErrorAction SilentlyContinue
    } catch {
        # ignore
    }

    # Wait for graceful shutdown (up to ~2s)
    for ($i = 0; $i -lt 20; $i++) {
        if (-not (Test-ProcessAlive -Pid $pid)) {
            break
        }
        Start-Sleep -Milliseconds 100
    }

    # If still running, escalate to force kill
    if (Test-ProcessAlive -Pid $pid) {
        try {
            Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
        } catch {
            # ignore
        }

        Start-Sleep -Milliseconds 100
    }

    if (Test-ProcessAlive -Pid $pid) {
        Write-Output (Write-Json @{ status = "failed"; error = "process still running" })
        exit 1
    }

    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Remove-Item $LogFile -Force -ErrorAction SilentlyContinue

    # Only delete ephemeral temp directories
    try {
        $fullSessionDir = [System.IO.Path]::GetFullPath($SessionDir).TrimEnd('\')
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')

        if ($fullSessionDir.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item $SessionDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    } catch {
        # ignore cleanup path errors
    }

    Write-Output (Write-Json @{ status = "stopped" })
}
else {
    Write-Output (Write-Json @{ status = "not_running" })
}