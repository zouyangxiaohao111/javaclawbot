package daemon;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * macOS launchd 服务实现
 * 
 * 对齐 OpenClaw 的 daemon/launchd.ts
 */
public class LaunchdService implements DaemonService {

    private static final String SERVICE_ID = "ai.javaclawbot.gateway";
    private static final String SERVICE_NAME = "javaclawbot";
    private static final String SERVICE_DESCRIPTION = "javaclawbot Gateway - Personal AI Assistant";
    
    private final Path launchAgentsDir;
    private final Path logDir;
    
    public LaunchdService() {
        String home = System.getProperty("user.home");
        this.launchAgentsDir = Paths.get(home, "Library", "LaunchAgents");
        this.logDir = Paths.get(home, ".javaclawbot", "logs");
    }
    
    @Override
    public ServiceResult install(ServiceConfig config) {
        try {
            // 检查是否在 macOS 上运行
            if (!isMacOS()) {
                return ServiceResult.failure("launchd 服务仅支持 macOS 系统");
            }
            
            // 创建目录
            Files.createDirectories(launchAgentsDir);
            Files.createDirectories(logDir);
            
            // 生成 plist 文件
            String plistContent = generatePlist(config);
            Path plistPath = getPlistPath();
            
            // 写入 plist 文件
            Files.writeString(plistPath, plistContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            // 加载服务
            ServiceResult loadResult = executeCommand("launchctl", "load", plistPath.toString());
            if (!loadResult.success()) {
                return ServiceResult.failure("加载服务失败", loadResult.error());
            }
            
            return ServiceResult.success("✓ 服务已安装到: " + plistPath);
            
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
            
            Path plistPath = getPlistPath();
            
            // 卸载服务
            if (isRunning()) {
                executeCommand("launchctl", "unload", plistPath.toString());
            }
            
            // 删除 plist 文件
            if (Files.exists(plistPath)) {
                Files.delete(plistPath);
            }
            
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
            
            Path plistPath = getPlistPath();
            
            // 如果已运行，先停止
            if (isRunning()) {
                executeCommand("launchctl", "unload", plistPath.toString());
            }
            
            // 加载并启动
            ServiceResult result = executeCommand("launchctl", "load", plistPath.toString());
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
            
            Path plistPath = getPlistPath();
            ServiceResult result = executeCommand("launchctl", "unload", plistPath.toString());
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
            
            Path plistPath = getPlistPath();
            
            // 卸载
            executeCommand("launchctl", "unload", plistPath.toString());
            
            // 等待一秒
            Thread.sleep(1000);
            
            // 重新加载
            ServiceResult result = executeCommand("launchctl", "load", plistPath.toString());
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
            
            // 检查服务是否在运行
            ProcessBuilder pb = new ProcessBuilder(
                "launchctl", "list", SERVICE_ID
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            
            String output = readProcessOutput(process);
            boolean running = !output.contains("could not find service") && 
                              !output.contains("No such process") &&
                              output.contains(SERVICE_ID);
            
            if (running) {
                // 尝试获取 PID
                String pid = extractPid(output);
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
        return Files.exists(getPlistPath());
    }
    
    @Override
    public boolean isRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "launchctl", "list", SERVICE_ID
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            
            String output = readProcessOutput(process);
            return !output.contains("could not find service") && 
                   !output.contains("No such process") &&
                   output.contains(SERVICE_ID);
                   
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public String getPlatform() {
        return "macos";
    }
    
    @Override
    public String getLabel() {
        return "launchd";
    }
    
    private Path getPlistPath() {
        return launchAgentsDir.resolve(SERVICE_ID + ".plist");
    }
    
    private String generatePlist(ServiceConfig config) {
        String logPath = logDir.resolve("gateway.log").toString();
        String errPath = logDir.resolve("gateway.err").toString();
        
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        sb.append("<plist version=\"1.0\">\n");
        sb.append("<dict>\n");
        
        // Label
        sb.append("    <key>Label</key>\n");
        sb.append("    <string>").append(SERVICE_ID).append("</string>\n");
        
        // ProgramArguments
        sb.append("    <key>ProgramArguments</key>\n");
        sb.append("    <array>\n");
        
        // 解析 execStart 为参数数组
        String[] args = parseExecStart(config.getExecStart());
        for (String arg : args) {
            sb.append("        <string>").append(escapeXml(arg)).append("</string>\n");
        }
        sb.append("    </array>\n");
        
        // WorkingDirectory
        sb.append("    <key>WorkingDirectory</key>\n");
        sb.append("    <string>").append(escapeXml(config.getWorkingDirectory())).append("</string>\n");
        
        // RunAtLoad - 启动时自动运行
        sb.append("    <key>RunAtLoad</key>\n");
        sb.append("    <true/>\n");
        
        // KeepAlive - 保持运行
        sb.append("    <key>KeepAlive</key>\n");
        sb.append("    <true/>\n");
        
        // StandardOutPath
        sb.append("    <key>StandardOutPath</key>\n");
        sb.append("    <string>").append(escapeXml(logPath)).append("</string>\n");
        
        // StandardErrorPath
        sb.append("    <key>StandardErrorPath</key>\n");
        sb.append("    <string>").append(escapeXml(errPath)).append("</string>\n");
        
        // EnvironmentVariables
        sb.append("    <key>EnvironmentVariables</key>\n");
        sb.append("    <dict>\n");
        if (config.javaHome() != null) {
            sb.append("        <key>JAVA_HOME</key>\n");
            sb.append("        <string>").append(escapeXml(config.javaHome())).append("</string>\n");
        }
        sb.append("    </dict>\n");
        
        sb.append("</dict>\n");
        sb.append("</plist>\n");
        
        return sb.toString();
    }
    
    private String[] parseExecStart(String execStart) {
        // 简单解析：按空格分割，但保留引号内的内容
        List<String> args = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        
        for (int i = 0; i < execStart.length(); i++) {
            char c = execStart.charAt(i);
            
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            args.add(current.toString());
        }
        
        return args.toArray(new String[0]);
    }
    
    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
    
    private String extractPid(String output) {
        // launchctl list 输出格式: PID Status Label
        try {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.contains(SERVICE_ID)) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 1 && !parts[0].equals("-")) {
                        return parts[0];
                    }
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
    
    private boolean isMacOS() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
}