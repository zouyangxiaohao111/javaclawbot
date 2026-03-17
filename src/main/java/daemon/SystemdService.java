package daemon;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Linux systemd 用户服务实现
 * 
 * 对齐 OpenClaw 的 daemon/systemd.ts
 */
public class SystemdService implements DaemonService {

    private static final String SERVICE_NAME = "javaclawbot";
    private static final String SERVICE_DESCRIPTION = "javaclawbot Gateway - Personal AI Assistant";
    
    private final Path unitDir;
    private final Path logDir;
    
    public SystemdService() {
        String home = System.getProperty("user.home");
        this.unitDir = Paths.get(home, ".config", "systemd", "user");
        this.logDir = Paths.get(home, ".javaclawbot", "logs");
    }
    
    @Override
    public ServiceResult install(ServiceConfig config) {
        try {
            // 检查是否在 Linux 上运行
            if (!isLinux()) {
                return ServiceResult.failure("systemd 服务仅支持 Linux 系统");
            }
            
            // 创建目录
            Files.createDirectories(unitDir);
            Files.createDirectories(logDir);
            
            // 生成 unit 文件
            String unitContent = generateUnitFile(config);
            Path unitPath = getUnitPath();
            
            // 写入 unit 文件
            Files.writeString(unitPath, unitContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            // 执行 daemon-reload
            ServiceResult reloadResult = executeCommand("systemctl", "--user", "daemon-reload");
            if (!reloadResult.success()) {
                return ServiceResult.failure("daemon-reload 失败", reloadResult.error());
            }
            
            // 启用服务
            ServiceResult enableResult = executeCommand("systemctl", "--user", "enable", SERVICE_NAME);
            if (!enableResult.success()) {
                return ServiceResult.failure("启用服务失败", enableResult.error());
            }
            
            return ServiceResult.success("✓ 服务已安装到: " + unitPath);
            
        } catch (Exception e) {
            return ServiceResult.failure("安装服务失败: " + e.getMessage());
        }
    }
    
    @Override
    public ServiceResult uninstall() {
        try {
            if (!isInstalled()) {
                return ServiceResult.failure("服务未安装");
            }
            
            // 先停止服务
            if (isRunning()) {
                executeCommand("systemctl", "--user", "stop", SERVICE_NAME);
            }
            
            // 禁用服务
            executeCommand("systemctl", "--user", "disable", SERVICE_NAME);
            
            // 删除 unit 文件
            Path unitPath = getUnitPath();
            if (Files.exists(unitPath)) {
                Files.delete(unitPath);
            }
            
            // 重新加载
            executeCommand("systemctl", "--user", "daemon-reload");
            
            return ServiceResult.success("✓ 服务已卸载");
            
        } catch (Exception e) {
            return ServiceResult.failure("卸载服务失败: " + e.getMessage());
        }
    }
    
    @Override
    public ServiceResult start() {
        try {
            if (!isInstalled()) {
                return ServiceResult.failure("服务未安装，请先运行: javaclawbot service install");
            }
            
            ServiceResult result = executeCommand("systemctl", "--user", "start", SERVICE_NAME);
            if (result.success()) {
                return ServiceResult.success("✓ 服务已启动");
            }
            return result;
            
        } catch (Exception e) {
            return ServiceResult.failure("启动服务失败: " + e.getMessage());
        }
    }
    
    @Override
    public ServiceResult stop() {
        try {
            if (!isInstalled()) {
                return ServiceResult.failure("服务未安装");
            }
            
            ServiceResult result = executeCommand("systemctl", "--user", "stop", SERVICE_NAME);
            if (result.success()) {
                return ServiceResult.success("✓ 服务已停止");
            }
            return result;
            
        } catch (Exception e) {
            return ServiceResult.failure("停止服务失败: " + e.getMessage());
        }
    }
    
    @Override
    public ServiceResult restart() {
        try {
            if (!isInstalled()) {
                return ServiceResult.failure("服务未安装，请先运行: javaclawbot service install");
            }
            
            ServiceResult result = executeCommand("systemctl", "--user", "restart", SERVICE_NAME);
            if (result.success()) {
                return ServiceResult.success("✓ 服务已重启");
            }
            return result;
            
        } catch (Exception e) {
            return ServiceResult.failure("重启服务失败: " + e.getMessage());
        }
    }
    
    @Override
    public ServiceStatus status() {
        try {
            if (!isInstalled()) {
                return ServiceStatus.notInstalled();
            }
            
            // 检查运行状态
            ProcessBuilder pb = new ProcessBuilder(
                "systemctl", "--user", "is-active", SERVICE_NAME
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            
            String output = readProcessOutput(process);
            boolean running = output.trim().equals("active");
            
            if (running) {
                // 获取 PID
                String pid = getPid();
                return ServiceStatus.running(pid);
            } else {
                return ServiceStatus.stopped();
            }
            
        } catch (Exception e) {
            return ServiceStatus.stopped();
        }
    }
    
    @Override
    public boolean isInstalled() {
        return Files.exists(getUnitPath());
    }
    
    @Override
    public boolean isRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "systemctl", "--user", "is-active", SERVICE_NAME
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            String output = readProcessOutput(process).trim();
            return output.equals("active");
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public String getPlatform() {
        return "linux";
    }
    
    @Override
    public String getLabel() {
        return "systemd";
    }
    
    private Path getUnitPath() {
        return unitDir.resolve(SERVICE_NAME + ".service");
    }
    
    private String generateUnitFile(ServiceConfig config) {
        String logPath = logDir.resolve("gateway.log").toString();
        String errPath = logDir.resolve("gateway.err").toString();
        
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=").append(SERVICE_DESCRIPTION).append("\n");
        sb.append("After=network.target\n");
        sb.append("Documentation=https://github.com/HKUDS/javaclawbot\n");
        sb.append("\n");
        sb.append("[Service]\n");
        sb.append("Type=simple\n");
        sb.append("ExecStart=").append(config.getExecStart()).append("\n");
        sb.append("WorkingDirectory=").append(config.getWorkingDirectory()).append("\n");
        sb.append("Restart=on-failure\n");
        sb.append("RestartSec=10\n");
        sb.append("StandardOutput=append:").append(logPath).append("\n");
        sb.append("StandardError=append:").append(errPath).append("\n");
        sb.append("\n");
        sb.append("# 环境变量\n");
        if (config.javaHome() != null) {
            sb.append("Environment=JAVA_HOME=").append(config.javaHome()).append("\n");
        }
        sb.append("\n");
        sb.append("[Install]\n");
        sb.append("WantedBy=default.target\n");
        
        return sb.toString();
    }
    
    private String getPid() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "systemctl", "--user", "show", "--property=MainPID", SERVICE_NAME
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            String output = readProcessOutput(process);
            // 输出格式: MainPID=12345
            if (output.contains("=")) {
                String pid = output.split("=")[1].trim();
                if (!pid.equals("0") && !pid.isEmpty()) {
                    return pid;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    private ServiceResult executeCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(30, TimeUnit.SECONDS);
            
            String output = readProcessOutput(process);
            int exitCode = process.exitValue();
            
            if (exitCode == 0) {
                return ServiceResult.success(output);
            } else {
                return ServiceResult.failure(output);
            }
            
        } catch (Exception e) {
            return ServiceResult.failure(e.getMessage());
        }
    }
    
    private String readProcessOutput(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
    
    private boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux");
    }
}