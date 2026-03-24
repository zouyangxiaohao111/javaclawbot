# ============================================
# javaclawbot 应用管理脚本 (PowerShell 版本)
# 文件名: clawbot.ps1
# 使用方法:
#   1. 命令行: .\clawbot.ps1 {start|stop|restart|status|log|help}
#   2. 双击执行: 显示交互式菜单
# ============================================

# 配置变量
$APP_NAME = "javaclawbot"
$APP_HOME = "D:\apps\javaclawbot"
$JAR_FILE = "javaclawbot.jar"
$LOG_FILE = "$APP_HOME\logs\clawbot.log"
$PID_FILE = "$APP_HOME\clawbot.pid"
$JAVA_OPTS = "-Xmx512m -Xms256m"
$JAVA_CMD = "C:\Program Files\Java17\jdk-17\bin\java.exe"

# 创建日志目录
if (-not (Test-Path "$APP_HOME\logs")) {
    New-Item -ItemType Directory -Path "$APP_HOME\logs" -Force | Out-Null
}

# 颜色定义
$Host.UI.RawUI.WindowTitle = "javaclawbot 管理工具"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor Green
}

function Write-Warning {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor Yellow
}

function Write-Error {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Get-Pid {
    if (Test-Path $PID_FILE) {
        $pid = Get-Content $PID_FILE -First 1
        return [int]$pid
    } else {
        # 通过进程名查找
        $process = Get-Process java -ErrorAction SilentlyContinue |
                   Where-Object { $_.CommandLine -like "*javaclawbot*" } |
                   Select-Object -First 1
        if ($process) {
            return $process.Id
        }
        return $null
    }
}

function Check-Prerequisites {
    # 检查应用目录是否存在
    if (-not (Test-Path $APP_HOME)) {
        Write-Error "应用目录不存在: $APP_HOME"
        return $false
    }

    # 检查jar文件是否存在
    if (-not (Test-Path "$APP_HOME\$JAR_FILE")) {
        Write-Error "Jar文件不存在: $APP_HOME\$JAR_FILE"
        return $false
    }

    # 检查Java是否安装
    $javaPath = Get-Command $JAVA_CMD -ErrorAction SilentlyContinue
    if (-not $javaPath) {
        Write-Error "Java未安装或未在PATH中找到"
        return $false
    }

    return $true
}

function Start-App {
    Write-Info "正在启动 $APP_NAME..."

    if (-not (Check-Prerequisites)) {
        return $false
    }

    $pid = Get-Pid
    if ($pid -and (Get-Process -Id $pid -ErrorAction SilentlyContinue)) {
        Write-Warning "$APP_NAME 已经在运行 (PID: $pid)"
        return $true
    }

    # 切换到应用目录
    try {
        Set-Location $APP_HOME
    } catch {
        Write-Error "无法切换到目录: $APP_HOME"
        return $false
    }

    # 启动应用
    Write-Info "执行命令: $JAVA_CMD $JAVA_OPTS -jar $JAR_FILE gateway"

    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = $JAVA_CMD
    $processInfo.Arguments = "$JAVA_OPTS -jar `"$JAR_FILE`" gateway"
    $processInfo.WorkingDirectory = $APP_HOME
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo

    try {
        $process.Start() | Out-Null
        $newPid = $process.Id
        $newPid.ToString() | Out-File $PID_FILE

        # 等待应用启动
        Write-Info "等待应用启动..."
        for ($i = 1; $i -le 5; $i++) {
            Write-Host "." -NoNewline
            Start-Sleep -Seconds 1
        }
        Write-Host ""

        if (Get-Process -Id $newPid -ErrorAction SilentlyContinue) {
            Write-Success "$APP_NAME 启动成功! (PID: $newPid)"
            Write-Info "日志文件: $LOG_FILE"
            return $true
        } else {
            Write-Error "$APP_NAME 启动失败，请检查日志"
            if (Test-Path $LOG_FILE) {
                Write-Info "最后10行日志:"
                Get-Content $LOG_FILE -Tail 10
            }
            Remove-Item $PID_FILE -ErrorAction SilentlyContinue
            return $false
        }
    } catch {
        Write-Error "启动失败: $_"
        return $false
    }
}

function Stop-App {
    Write-Info "正在停止 $APP_NAME..."

    $pid = Get-Pid
    if (-not $pid) {
        Write-Warning "$APP_NAME 未在运行"
        # 清理可能存在的PID文件
        if (Test-Path $PID_FILE) {
            Remove-Item $PID_FILE
        }
        return $true
    }

    $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
    if ($process) {
        Write-Info "发送终止信号到进程 (PID: $pid)..."

        # 尝试优雅停止
        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue

        # 等待进程停止
        Write-Info "等待进程停止..."
        $waitCount = 0
        while ($waitCount -lt 10) {
            if (-not (Get-Process -Id $pid -ErrorAction SilentlyContinue)) {
                break
            }
            Write-Host "." -NoNewline
            Start-Sleep -Seconds 1
            $waitCount++
        }
        Write-Host ""

        if (Get-Process -Id $pid -ErrorAction SilentlyContinue) {
            Write-Error "无法停止进程 (PID: $pid)"
            return $false
        } else {
            Write-Success "$APP_NAME 已停止"
        }
    } else {
        Write-Warning "进程不存在 (PID: $pid)，清理PID文件"
    }

    # 清理PID文件
    if (Test-Path $PID_FILE) {
        Remove-Item $PID_FILE
    }

    return $true
}

function Restart-App {
    Write-Info "正在重启 $APP_NAME..."
    if (-not (Stop-App)) {
        return $false
    }
    Start-Sleep -Seconds 2
    return Start-App
}

function Show-Status {
    Write-Info "检查 $APP_NAME 状态..."

    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host "                   应用状态信息" -ForegroundColor Cyan
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host ""

    $pid = Get-Pid
    if ($pid -and (Get-Process -Id $pid -ErrorAction SilentlyContinue)) {
        Write-Success "$APP_NAME 正在运行 (PID: $pid)"
        Write-Host ""

        Write-Host "进程信息:" -ForegroundColor Blue
        Get-Process -Id $pid | Format-Table Id, ProcessName, CPU, WorkingSet, StartTime -AutoSize

        Write-Host ""
        Write-Host "系统信息:" -ForegroundColor Blue
        Write-Host "应用目录: $APP_HOME"
        Write-Host "日志文件: $LOG_FILE"
        Write-Host "PID文件: $PID_FILE"

        # 获取Java版本
        $javaVersion = & $JAVA_CMD -version 2>&1 | Select-Object -First 1
        Write-Host "Java版本: $javaVersion"

        Write-Host ""
        Write-Host "最近日志 (最后5行):" -ForegroundColor Blue
        if (Test-Path $LOG_FILE) {
            Get-Content $LOG_FILE -Tail 5
        } else {
            Write-Host "日志文件不存在"
        }

        return $true
    } else {
        Write-Warning "$APP_NAME 未在运行"
        Write-Host ""
        Write-Host "系统信息:" -ForegroundColor Blue
        Write-Host "应用目录: $APP_HOME"

        if (Test-Path $APP_HOME) {
            Write-Host "目录状态: 存在" -ForegroundColor Green
        } else {
            Write-Host "目录状态: 不存在" -ForegroundColor Red
        }

        if (Test-Path "$APP_HOME\$JAR_FILE") {
            $jarSize = (Get-Item "$APP_HOME\$JAR_FILE").Length
            $jarSizeMB = [math]::Round($jarSize / 1MB, 2)
            Write-Host "Jar文件: 存在 ($jarSizeMB MB)" -ForegroundColor Green
        } else {
            Write-Host "Jar文件: 不存在" -ForegroundColor Red
        }

        if (Get-Command $JAVA_CMD -ErrorAction SilentlyContinue) {
            Write-Host "Java状态: 已安装" -ForegroundColor Green
        } else {
            Write-Host "Java状态: 未安装" -ForegroundColor Red
        }

        return $false
    }
}

function Show-Log {
    if (-not (Test-Path $LOG_FILE)) {
        Write-Warning "日志文件不存在: $LOG_FILE"
        return $false
    }

    Write-Info "查看日志 (Ctrl+C 退出查看): $LOG_FILE"
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host "                     应用日志" -ForegroundColor Cyan
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "日志信息:" -ForegroundColor Blue
    $logSize = (Get-Item $LOG_FILE).Length
    $logSizeKB = [math]::Round($logSize / 1KB, 2)
    Write-Host "文件大小: $logSizeKB KB"
    Write-Host "修改时间: $(Get-Item $LOG_FILE).LastWriteTime)"
    Write-Host ""
    Write-Host "日志内容 (最后50行，实时更新):" -ForegroundColor Blue
    Write-Host "------------------------------------------------------" -ForegroundColor Blue

    Get-Content $LOG_FILE -Wait -Tail 50
    return $true
}

function Show-FullLog {
    if (-not (Test-Path $LOG_FILE)) {
        Write-Warning "日志文件不存在: $LOG_FILE"
        return $false
    }

    Write-Info "查看完整日志: $LOG_FILE"
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host "                    完整应用日志" -ForegroundColor Cyan
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "日志信息:" -ForegroundColor Blue
    $logSize = (Get-Item $LOG_FILE).Length
    $logSizeKB = [math]::Round($logSize / 1KB, 2)
    $lineCount = (Get-Content $LOG_FILE).Count
    Write-Host "文件大小: $logSizeKB KB"
    Write-Host "行数: $lineCount"
    Write-Host ""
    Write-Host "日志内容 (最后100行):" -ForegroundColor Blue
    Write-Host "------------------------------------------------------" -ForegroundColor Blue

    Get-Content $LOG_FILE -Tail 100
    return $true
}

function Clear-Log {
    if (-not (Test-Path $LOG_FILE)) {
        Write-Warning "日志文件不存在: $LOG_FILE"
        return $true
    }

    Write-Info "清理日志文件..."
    $logSize = (Get-Item $LOG_FILE).Length
    $logSizeKB = [math]::Round($logSize / 1KB, 2)

    # 备份日志文件
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $backupFile = "$LOG_FILE.backup.$timestamp"
    Copy-Item $LOG_FILE $backupFile

    # 清空日志文件
    Clear-Content $LOG_FILE

    Write-Success "日志已清理 (原日志已备份到: $backupFile, 原大小: $logSizeKB KB)"
    return $true
}

function Show-SystemInfo {
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host "                   系统配置信息" -ForegroundColor Cyan
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host ""

    Write-Host "应用配置:" -ForegroundColor Blue
    Write-Host "应用名称: $APP_NAME"
    Write-Host "应用目录: $APP_HOME"
    Write-Host "Jar文件: $JAR_FILE"
    Write-Host "日志文件: $LOG_FILE"
    Write-Host "PID文件: $PID_FILE"
    Write-Host "Java参数: $JAVA_OPTS"

    Write-Host ""
    Write-Host "系统状态:" -ForegroundColor Blue
    Write-Host "当前用户: $env:USERNAME"
    Write-Host "计算机名: $env:COMPUTERNAME"
    Write-Host "当前时间: $(Get-Date)"

    Write-Host ""
    Write-Host "Java环境:" -ForegroundColor Blue
    $javaPath = Get-Command $JAVA_CMD -ErrorAction SilentlyContinue
    if ($javaPath) {
        Write-Host "Java路径: $($javaPath.Source)"
        Write-Host "Java版本:"
        & $JAVA_CMD -version 2>&1 | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host "Java: 未找到" -ForegroundColor Red
    }

    Write-Host ""
    Write-Host "目录状态:" -ForegroundColor Blue
    if (Test-Path $APP_HOME) {
        Write-Host "应用目录: 存在" -ForegroundColor Green
        Write-Host "目录内容:"
        Get-ChildItem $APP_HOME | Select-Object -First 10 Name, Length, LastWriteTime | Format-Table -AutoSize
    } else {
        Write-Host "应用目录: 不存在" -ForegroundColor Red
    }

    if (Test-Path "$APP_HOME\$JAR_FILE") {
        $jarSize = (Get-Item "$APP_HOME\$JAR_FILE").Length
        $jarSizeMB = [math]::Round($jarSize / 1MB, 2)
        Write-Host ""
        Write-Host "Jar文件: 存在 ($jarSizeMB MB)" -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "Jar文件: 不存在" -ForegroundColor Red
    }
}

function Show-Help {
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host "                  javaclawbot 管理工具" -ForegroundColor Cyan
    Write-Host "======================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "使用方法:"
    Write-Host "  1. 命令行模式: .\$($MyInvocation.MyCommand.Name) {start|stop|restart|status|log|help}"
    Write-Host "  2. 交互模式: 直接运行脚本(不带参数)或双击运行"
    Write-Host ""
    Write-Host "命令说明:"
    Write-Host "  start       启动应用程序"
    Write-Host "  stop        停止应用程序"
    Write-Host "  restart     重启应用程序"
    Write-Host "  status      查看应用程序状态"
    Write-Host "  log         实时查看应用程序日志"
    Write-Host "  fullog      查看完整日志(不跟踪)"
    Write-Host "  clean       清理日志文件"
    Write-Host "  sysinfo     显示系统配置信息"
    Write-Host "  help        显示此帮助信息"
    Write-Host "  menu        显示交互式菜单"
    Write-Host ""
    Write-Host "配置信息:"
    Write-Host "  应用目录: $APP_HOME"
    Write-Host "  Jar文件:  $JAR_FILE"
    Write-Host "  日志文件: $LOG_FILE"
    Write-Host "  Java参数: $JAVA_OPTS"
    Write-Host ""
    Write-Host "示例:"
    Write-Host "  .\$($MyInvocation.MyCommand.Name) start          # 启动应用"
    Write-Host "  .\$($MyInvocation.MyCommand.Name) status         # 查看状态"
    Write-Host "  .\$($MyInvocation.MyCommand.Name) log            # 查看实时日志"
    Write-Host "  .\$($MyInvocation.MyCommand.Name) menu           # 进入交互菜单"
    Write-Host ""
    Write-Host "交互模式:"
    Write-Host "  直接运行脚本或双击文件，会显示交互式菜单供选择操作"
}

function Show-Menu {
    do {
        Clear-Host
        Write-Host "======================================================" -ForegroundColor Cyan
        Write-Host "               javaclawbot 管理工具" -ForegroundColor Cyan
        Write-Host "======================================================" -ForegroundColor Cyan
        Write-Host ""

        # 显示当前状态
        $pid = Get-Pid
        if ($pid -and (Get-Process -Id $pid -ErrorAction SilentlyContinue)) {
            Write-Host "当前状态: 运行中 (PID: $pid)" -ForegroundColor Green
        } else {
            Write-Host "当前状态: 已停止" -ForegroundColor Yellow
        }

        Write-Host ""
        Write-Host "请选择操作:" -ForegroundColor Magenta
        Write-Host ""
        Write-Host "  1. 启动应用"
        Write-Host "  2. 停止应用"
        Write-Host "  3. 重启应用"
        Write-Host "  4. 查看状态"
        Write-Host "  5. 实时查看日志"
        Write-Host "  6. 查看完整日志"
        Write-Host "  7. 清理日志"
        Write-Host "  8. 系统信息"
        Write-Host "  9. 帮助"
        Write-Host "  0. 退出"
        Write-Host ""
        Write-Host "======================================================" -ForegroundColor Cyan
        Write-Host ""

        $choice = Read-Host "请输入选项 [0-9]"

        switch ($choice) {
            "1" {
                Write-Host ""
                Start-App
            }
            "2" {
                Write-Host ""
                Stop-App
            }
            "3" {
                Write-Host ""
                Restart-App
            }
            "4" {
                Write-Host ""
                Show-Status
            }
            "5" {
                Write-Host ""
                Show-Log
                # 日志查看后会退出，需要重新显示菜单
                continue
            }
            "6" {
                Write-Host ""
                Show-FullLog
            }
            "7" {
                Write-Host ""
                Clear-Log
            }
            "8" {
                Write-Host ""
                Show-SystemInfo
            }
            "9" {
                Write-Host ""
                Show-Help
            }
            "0" {
                Write-Host ""
                Write-Info "感谢使用，再见！"
                Start-Sleep -Seconds 2
                exit 0
            }
            default {
                Write-Host ""
                Write-Error "无效选项，请重新输入"
                Start-Sleep -Seconds 2
                continue
            }
        }

        # 等待用户按键继续
        if ($choice -ne "0" -and $choice -ne "5") {
            Write-Host ""
            Write-Host "按 Enter 键继续..." -ForegroundColor Yellow
            Read-Host
        }
    } while ($true)
}

# 主函数
function Main {
    param([string]$Command)

    if (-not $Command) {
        Show-Menu
        return
    }

    switch ($Command.ToLower()) {
        "start" { Start-App }
        "stop" { Stop-App }
        "restart" { Restart-App }
        "status" { Show-Status }
        "log" { Show-Log }
        "fullog" { Show-FullLog }
        "clean" { Clear-Log }
        "sysinfo" { Show-SystemInfo }
        "help" { Show-Help }
        "menu" { Show-Menu }
        default {
            Write-Error "无效的命令: $Command"
            Write-Host ""
            Show-Help
        }
    }
}

# 脚本入口
if ($args.Count -eq 0) {
    Show-Menu
} else {
    Main $args[0]
}