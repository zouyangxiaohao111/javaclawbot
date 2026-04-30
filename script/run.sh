#!/bin/bash

# javaclawbot 应用管理脚本
# 文件名: clawbot.sh
# 功能: 支持命令行参数和交互式菜单两种模式
# 使用方法:
#   1. 命令行: ./clawbot.sh {start|stop|restart|status|log|help}
#   2. 双击执行: 显示交互式菜单

APP_NAME="javaclawbot"
APP_HOME="$HOME/apps/javaclawbot"
JAR_FILE="javaclawbot.jar"
LOG_FILE="$APP_HOME/logs/clawbot.log"
PID_FILE="$APP_HOME/clawbot.pid"
JAVA_OPTS="-Xmx512m -Xms256m"
JAVA_CMD="java"

# 检查并创建日志目录
mkdir -p "$APP_HOME/logs"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # 无颜色
BOLD='\033[1m'

# 打印带颜色的消息
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 打印横幅
print_banner() {
    clear
    echo -e "${CYAN}${BOLD}"
    echo "======================================================"
    echo "               javaclawbot 管理工具"
    echo "======================================================"
    echo -e "${NC}"
}

# 检查必要文件和环境
check_prerequisites() {
    # 检查应用目录是否存在
    if [ ! -d "$APP_HOME" ]; then
        print_error "应用目录不存在: $APP_HOME"
        return 1
    fi

    # 检查jar文件是否存在
    if [ ! -f "$APP_HOME/$JAR_FILE" ]; then
        print_error "Jar文件不存在: $APP_HOME/$JAR_FILE"
        return 1
    fi

    # 检查Java是否安装
    if ! command -v $JAVA_CMD &> /dev/null; then
        print_error "Java未安装或未在PATH中找到"
        return 1
    fi

    return 0
}

# 获取进程ID
get_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    else
        # 通过进程名查找
        pgrep -f "java.*$JAR_FILE" || echo ""
    fi
}

# 启动应用
start_app() {
    print_info "正在启动 $APP_NAME..."

    if ! check_prerequisites; then
        return 1
    fi

    local pid=$(get_pid)
    if [ -n "$pid" ] && ps -p "$pid" > /dev/null 2>&1; then
        print_warning "$APP_NAME 已经在运行 (PID: $pid)"
        return 0
    fi

    # 切换到应用目录
    cd "$APP_HOME" || {
        print_error "无法切换到目录: $APP_HOME"
        return 1
    }

    # 启动应用，输出重定向到日志文件
    print_info "执行命令: $JAVA_CMD $JAVA_OPTS -jar $JAR_FILE gateway"
    nohup $JAVA_CMD $JAVA_OPTS -jar "$JAR_FILE" gateway >> "$LOG_FILE" 2>&1 &

    local new_pid=$!
    echo $new_pid > "$PID_FILE"

    # 等待几秒检查进程是否启动成功
    print_info "等待应用启动..."
    for i in {1..5}; do
        echo -n "."
        sleep 1
    done
    echo ""

    if ps -p "$new_pid" > /dev/null 2>&1; then
        print_success "$APP_NAME 启动成功! (PID: $new_pid)"
        print_info "日志文件: $LOG_FILE"
        return 0
    else
        print_error "$APP_NAME 启动失败，请检查日志"
        echo ""
        print_info "最后10行日志:"
        tail -10 "$LOG_FILE"
        rm -f "$PID_FILE"
        return 1
    fi
}

# 停止应用
stop_app() {
    print_info "正在停止 $APP_NAME..."

    local pid=$(get_pid)

    if [ -z "$pid" ]; then
        print_warning "$APP_NAME 未在运行"
        return 0
    fi

    if ps -p "$pid" > /dev/null 2>&1; then
        # 优雅停止
        print_info "发送终止信号到进程 (PID: $pid)..."
        kill -15 "$pid" 2>/dev/null

        # 等待最多10秒
        print_info "等待进程停止..."
        local wait_count=0
        while [ $wait_count -lt 10 ]; do
            if ! ps -p "$pid" > /dev/null 2>&1; then
                break
            fi
            echo -n "."
            sleep 1
            ((wait_count++))
        done
        echo ""

        # 如果仍然在运行，强制终止
        if ps -p "$pid" > /dev/null 2>&1; then
            print_warning "进程仍在运行，强制终止..."
            kill -9 "$pid" 2>/dev/null
            sleep 1
        fi

        if ps -p "$pid" > /dev/null 2>&1; then
            print_error "无法停止进程 (PID: $pid)"
            return 1
        else
            rm -f "$PID_FILE"
            print_success "$APP_NAME 已停止"
            return 0
        fi
    else
        print_warning "进程不存在 (PID: $pid)，清理PID文件"
        rm -f "$PID_FILE"
        return 0
    fi
}

# 重启应用
restart_app() {
    stop_app
    sleep 2
    start_app
}

# 查看应用状态
status_app() {
    print_info "检查 $APP_NAME 状态..."

    local pid=$(get_pid)

    echo -e "${CYAN}${BOLD}"
    echo "======================================================"
    echo "                   应用状态信息"
    echo "======================================================"
    echo -e "${NC}"

    if [ -n "$pid" ] && ps -p "$pid" > /dev/null 2>&1; then
        print_success "$APP_NAME 正在运行 (PID: $pid)"
        echo ""

        # 显示进程信息
        echo -e "${BLUE}进程信息:${NC}"
        ps -p "$pid" -o pid,ppid,pcpu,pmem,etime,cmd | tail -1

        # 显示运行时间
        echo ""
        echo -e "${BLUE}系统信息:${NC}"
        echo "应用目录: $APP_HOME"
        echo "日志文件: $LOG_FILE"
        echo "PID文件: $PID_FILE"
        echo "Java版本: $($JAVA_CMD -version 2>&1 | head -1)"

        # 尝试获取内存使用情况
        echo ""
        echo -e "${BLUE}内存使用:${NC}"
        if command -v jstat &> /dev/null; then
            jstat -gc "$pid" 2>/dev/null || echo "无法获取详细内存信息"
        else
            echo "jstat 不可用，无法获取详细内存信息"
        fi

        # 显示最近日志
        echo ""
        echo -e "${BLUE}最近日志 (最后5行):${NC}"
        if [ -f "$LOG_FILE" ]; then
            tail -5 "$LOG_FILE"
        else
            echo "日志文件不存在"
        fi

        return 0
    else
        print_warning "$APP_NAME 未在运行"
        echo ""
        echo -e "${BLUE}系统信息:${NC}"
        echo "应用目录: $APP_HOME"
        if [ -d "$APP_HOME" ]; then
            echo "目录状态: 存在"
        else
            echo "目录状态: ${RED}不存在${NC}"
        fi

        if [ -f "$APP_HOME/$JAR_FILE" ]; then
            echo "Jar文件: 存在 ($(ls -lh "$APP_HOME/$JAR_FILE" | awk '{print $5}'))"
        else
            echo "Jar文件: ${RED}不存在${NC}"
        fi

        echo "Java状态: $(command -v $JAVA_CMD > /dev/null && echo "已安装" || echo "${RED}未安装${NC}")"

        return 1
    fi
}

# 查看日志
view_log() {
    if [ ! -f "$LOG_FILE" ]; then
        print_warning "日志文件不存在: $LOG_FILE"
        return 1
    fi

    print_info "查看日志 (Ctrl+C 退出查看): $LOG_FILE"
    echo -e "${CYAN}${BOLD}"
    echo "======================================================"
    echo "                     应用日志"
    echo "======================================================"
    echo -e "${NC}"

    # 显示日志文件信息
    echo -e "${BLUE}日志信息:${NC}"
    echo "文件大小: $(ls -lh "$LOG_FILE" | awk '{print $5}')"
    echo "修改时间: $(stat -f "%Sm" "$LOG_FILE" 2>/dev/null || date -r "$LOG_FILE")"
    echo ""
    echo -e "${BLUE}日志内容 (最后50行，实时更新):${NC}"
    echo "------------------------------------------------------"

    # 显示最后50行并实时跟踪
    tail -50 -f "$LOG_FILE"
}

# 查看完整日志（不跟踪）
view_full_log() {
    if [ ! -f "$LOG_FILE" ]; then
        print_warning "日志文件不存在: $LOG_FILE"
        return 1
    fi

    print_info "查看完整日志: $LOG_FILE"
    echo -e "${CYAN}${BOLD}"
    echo "======================================================"
    echo "                    完整应用日志"
    echo "======================================================"
    echo -e "${NC}"

    # 显示日志文件信息
    echo -e "${BLUE}日志信息:${NC}"
    echo "文件大小: $(ls -lh "$LOG_FILE" | awk '{print $5}')"
    echo "行数: $(wc -l < "$LOG_FILE")"
    echo ""
    echo -e "${BLUE}日志内容 (最后100行):${NC}"
    echo "------------------------------------------------------"

    # 显示最后100行
    tail -100 "$LOG_FILE"
}

# 清理日志
clean_log() {
    if [ ! -f "$LOG_FILE" ]; then
        print_warning "日志文件不存在: $LOG_FILE"
        return 0
    fi

    print_info "清理日志文件..."
    local log_size=$(ls -lh "$LOG_FILE" | awk '{print $5}')

    # 备份日志文件
    local backup_file="${LOG_FILE}.backup.$(date +%Y%m%d_%H%M%S)"
    cp "$LOG_FILE" "$backup_file"

    # 清空日志文件
    > "$LOG_FILE"

    print_success "日志已清理 (原日志已备份到: $backup_file, 原大小: $log_size)"
}

# 显示系统信息
show_system_info() {
    echo -e "${CYAN}${BOLD}"
    echo "======================================================"
    echo "                   系统配置信息"
    echo "======================================================"
    echo -e "${NC}"

    echo -e "${BLUE}应用配置:${NC}"
    echo "应用名称: $APP_NAME"
    echo "应用目录: $APP_HOME"
    echo "Jar文件: $JAR_FILE"
    echo "日志文件: $LOG_FILE"
    echo "PID文件: $PID_FILE"
    echo "Java参数: $JAVA_OPTS"

    echo ""
    echo -e "${BLUE}系统状态:${NC}"
    echo "当前用户: $(whoami)"
    echo "主机名: $(hostname)"
    echo "当前时间: $(date)"

    echo ""
    echo -e "${BLUE}Java环境:${NC}"
    if command -v $JAVA_CMD &> /dev/null; then
        echo "Java路径: $(which $JAVA_CMD)"
        echo "Java版本:"
        $JAVA_CMD -version 2>&1 | while read -r line; do
            echo "  $line"
        done
    else
        echo "Java: ${RED}未找到${NC}"
    fi

    echo ""
    echo -e "${BLUE}目录状态:${NC}"
    if [ -d "$APP_HOME" ]; then
        echo "应用目录: ${GREEN}存在${NC}"
        echo "目录内容:"
        ls -la "$APP_HOME" | head -10
    else
        echo "应用目录: ${RED}不存在${NC}"
    fi

    if [ -f "$APP_HOME/$JAR_FILE" ]; then
        echo ""
        echo "Jar文件: ${GREEN}存在${NC} ($(ls -lh "$APP_HOME/$JAR_FILE" | awk '{print $5}'))"
    else
        echo ""
        echo "Jar文件: ${RED}不存在${NC}"
    fi
}

# 显示帮助信息
show_help() {
    echo -e "${CYAN}${BOLD}"
    echo "======================================================"
    echo "                  javaclawbot 管理工具"
    echo "======================================================"
    echo -e "${NC}"

    cat << EOF
使用方法:
  1. 命令行模式: $0 {start|stop|restart|status|log|help}
  2. 交互模式: 直接运行脚本(不带参数)或双击运行

命令说明:
  start       启动应用程序
  stop        停止应用程序
  restart     重启应用程序
  status      查看应用程序状态
  log         实时查看应用程序日志
  fullog      查看完整日志(不跟踪)
  clean       清理日志文件
  sysinfo     显示系统配置信息
  help        显示此帮助信息
  menu        显示交互式菜单
  exit        退出脚本

配置信息:
  应用目录: $APP_HOME
  Jar文件:  $JAR_FILE
  日志文件: $LOG_FILE
  Java参数: $JAVA_OPTS

示例:
  $0 start          # 启动应用
  $0 status         # 查看状态
  $0 log            # 查看实时日志
  $0 menu           # 进入交互菜单

交互模式:
  直接运行脚本或双击文件，会显示交互式菜单供选择操作
EOF
}

# 显示交互式菜单
show_menu() {
    local choice

    while true; do
        print_banner

        # 显示当前状态
        local pid=$(get_pid)
        if [ -n "$pid" ] && ps -p "$pid" > /dev/null 2>&1; then
            echo -e "当前状态: ${GREEN}运行中${NC} (PID: $pid)"
        else
            echo -e "当前状态: ${YELLOW}已停止${NC}"
        fi

        echo ""
        echo -e "${MAGENTA}${BOLD}请选择操作:${NC}"
        echo ""
        echo "  1. 启动应用"
        echo "  2. 停止应用"
        echo "  3. 重启应用"
        echo "  4. 查看状态"
        echo "  5. 实时查看日志"
        echo "  6. 查看完整日志"
        echo "  7. 清理日志"
        echo "  8. 系统信息"
        echo "  9. 帮助"
        echo "  0. 退出"
        echo ""
        echo -e "${CYAN}======================================================${NC}"
        echo ""

        read -p "请输入选项 [0-9]: " choice

        case $choice in
            1)
                echo ""
                start_app
                ;;
            2)
                echo ""
                stop_app
                ;;
            3)
                echo ""
                restart_app
                ;;
            4)
                echo ""
                status_app
                ;;
            5)
                echo ""
                view_log
                ;;
            6)
                echo ""
                view_full_log
                ;;
            7)
                echo ""
                clean_log
                ;;
            8)
                echo ""
                show_system_info
                ;;
            9)
                echo ""
                show_help
                ;;
            0)
                echo ""
                print_info "感谢使用，再见！"
                exit 0
                ;;
            *)
                echo ""
                print_error "无效选项，请重新输入"
                ;;
        esac

        # 等待用户按键继续
        if [ "$choice" != "0" ] && [ "$choice" != "5" ]; then
            echo ""
            echo -e "${YELLOW}按 Enter 键继续...${NC}"
            read -r
        fi
    done
}

# 主函数
main() {
    # 如果没有参数，显示菜单
    if [ $# -eq 0 ]; then
        show_menu
        exit 0
    fi

    # 处理命令行参数
    case "$1" in
        start)
            start_app
            ;;
        stop)
            stop_app
            ;;
        restart)
            restart_app
            ;;
        status)
            status_app
            ;;
        log)
            view_log
            ;;
        fullog)
            view_full_log
            ;;
        clean)
            clean_log
            ;;
        sysinfo)
            show_system_info
            ;;
        help|--help|-h)
            show_help
            ;;
        menu)
            show_menu
            ;;
        *)
            print_error "无效的命令: $1"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

# 设置脚本在错误时退出
set -e

# 运行主函数
main "$@"