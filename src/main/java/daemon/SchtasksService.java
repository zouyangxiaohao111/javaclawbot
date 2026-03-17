package daemon;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Windows 计划任务服务实现
 * 
 * 对齐 OpenClaw 的 daemon/schtasks.ts
 */
public class SchtasksService implements DaemonService {

    private static final String TASK_NAME = "javaclawbot-gateway";
    private static final String SERVICE_DESCRIPTION = "javaclawbot Gateway - Personal AI Assistant";
    
    private final Path logDir;
    private final Path scriptPath;
    
    public SchtasksService() {
        String home = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        
        this.logDir = Paths.get(home, ".javaclawbot", "logs");
        this.scriptPath = Paths.get(appData != null ? appData : home, 
            "javaclawbot", "start-gateway.bat");
    }
    
    @Override
    public ServiceResult install(ServiceConfig config) {
        try {
            // 检查是否在 Windows 上运行
            if (!isWindows()) {
                return ServiceResult.failure("计划任务服务仅支持 Windows 系统");
            }
            
            // 创建目录
            Files.createDirectories(logDir);
            Files.createDirectories(scriptPath.getParent());
            
            // 生成启动脚本
            String scriptContent = generateStartScript(config);
            Files.writeString(scriptPath, scriptContent, 
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            // 创建计划任务
            String xml = generateTaskXml(config);
            Path xmlPath = scriptPath.resolveSibling("task.xml");
            Files.writeString(xmlPath, xml);
            
            // 删除已存在的任务
            executeCommand("schtasks", "/Delete", "/TN", TASK_NAME, "/F");
            
            // 创建新任务
            ServiceResult result = executeCommand(
                "schtasks", "/Create", 
                "/TN", TASK_NAME,
                "/XML", xmlPath.toString(),
                "/F"
            );
            
            // 删除临时 XML 文件
            Files.deleteIfExists(xmlPath);
            
            if (result.success()) {
                return ServiceResult.success("✓ 服务已安装（计划任务: " + TASK_NAME + "）");
            }
            return result;
            
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
            
            // 停止任务
            executeCommand("schtasks", "/End", "/TN", TASK_NAME);
            
            // 删除任务
            ServiceResult result = executeCommand(
                "schtasks", "/Delete", "/TN", TASK_NAME, "/F"
            );
            
            // 删除启动脚本
            if (Files.exists(scriptPath)) {
                Files.delete(scriptPath);
            }
            
            if (result.success()) {
                return ServiceResult.success("✓ 服务已卸载");
            }
            return result;
            
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
            
            ServiceResult result = executeCommand(
                "schtasks", "/Run", "/TN", TASK_NAME
            );
            
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
            
            ServiceResult result = executeCommand(
                "schtasks", "/End", "/TN", TASK_NAME
            );
            
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
            
            // 停止
            executeCommand("schtasks", "/End", "/TN", TASK_NAME);
            
            // 等待
            Thread.sleep(2000);
            
            // 启动
            ServiceResult result = executeCommand(
                "schtasks", "/Run", "/TN", TASK_NAME
            );
            
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
            
            // 查询任务状态
            ProcessBuilder pb = new ProcessBuilder(
                "schtasks", "/Query", "/TN", TASK_NAME, "/V", "/FO", "LIST"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(10, TimeUnit.SECONDS);
            
            String output = readProcessOutput(process);
            
            // 解析状态
            boolean running = output.contains("Running") || 
                              output.contains("正在运行");
            
            if (running) {
                return ServiceStatus.running(null);
            } else if (output.contains("Ready") || output.contains("就绪")) {
                return ServiceStatus.stopped();
            } else {
                return ServiceStatus.stopped();
            }
            
        } catch (Exception e) {
            return ServiceStatus.stopped();
        }
    }
    
    @Override
    public boolean isInstalled() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "schtasks", "/Query", "/TN", TASK_NAME
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(10, TimeUnit.SECONDS);
            
            String output = readProcessOutput(process);
            return !output.contains("cannot find") && 
                   !output.contains("找不到") &&
                   output.contains(TASK_NAME);
                   
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "schtasks", "/Query", "/TN", TASK_NAME, "/V", "/FO", "LIST"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(10, TimeUnit.SECONDS);
            
            String output = readProcessOutput(process);
            return output.contains("Running") || output.contains("正在运行");
            
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public String getPlatform() {
        return "windows";
    }
    
    @Override
    public String getLabel() {
        return "计划任务";
    }
    
    private String generateStartScript(ServiceConfig config) {
        String logPath = logDir.resolve("gateway.log").toString();
        String errPath = logDir.resolve("gateway.err").toString();
        
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\n");
        sb.append("REM javaclawbot Gateway 启动脚本\n");
        sb.append("\n");
        
        if (config.javaHome() != null) {
            sb.append("set JAVA_HOME=").append(config.javaHome()).append("\n");
        }
        
        sb.append("cd /d ").append(config.getWorkingDirectory()).append("\n");
        sb.append("\n");
        sb.append("echo Starting javaclawbot Gateway... >> \"").append(logPath).append("\"\n");
        sb.append("echo %date% %time% >> \"").append(logPath).append("\"\n");
        sb.append("\n");
        sb.append(":restart\n");
        sb.append(config.getExecStart().replace("java", 
            config.javaHome() != null ? config.javaHome() + "\\bin\\java.exe" : "java"))
            .append(" >> \"").append(logPath).append("\"")
            .append(" 2>> \"").append(errPath).append("\"\n");
        sb.append("\n");
        sb.append("REM 自动重启\n");
        sb.append("echo Gateway stopped, restarting in 10 seconds... >> \"").append(logPath).append("\"\n");
        sb.append("timeout /t 10 /nobreak > nul\n");
        sb.append("goto restart\n");
        
        return sb.toString();
    }
    
    private String generateTaskXml(ServiceConfig config) {
        String user = System.getProperty("user.name");
        
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n");
        sb.append("<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\n");
        
        // RegistrationInfo
        sb.append("  <RegistrationInfo>\n");
        sb.append("    <Description>").append(SERVICE_DESCRIPTION).append("</Description>\n");
        sb.append("    <URI>\\").append(TASK_NAME).append("</URI>\n");
        sb.append("  </RegistrationInfo>\n");
        
        // Triggers - 登录时启动
        sb.append("  <Triggers>\n");
        sb.append("    <LogonTrigger>\n");
        sb.append("      <Enabled>true</Enabled>\n");
        sb.append("    </LogonTrigger>\n");
        sb.append("  </Triggers>\n");
        
        // Principals
        sb.append("  <Principals>\n");
        sb.append("    <Principal id=\"Author\">\n");
        sb.append("      <UserId>").append(user).append("</UserId>\n");
        sb.append("      <LogonType>InteractiveToken</LogonType>\n");
        sb.append("      <RunLevel>LeastPrivilege</RunLevel>\n");
        sb.append("    </Principal>\n");
        sb.append("  </Principals>\n");
        
        // Settings
        sb.append("  <Settings>\n");
        sb.append("    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\n");
        sb.append("    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\n");
        sb.append("    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\n");
        sb.append("    <AllowHardTerminate>true</AllowHardTerminate>\n");
        sb.append("    <StartWhenAvailable>true</StartWhenAvailable>\n");
        sb.append("    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>\n");
        sb.append("    <AllowStartOnDemand>true</AllowStartOnDemand>\n");
        sb.append("    <Enabled>true</Enabled>\n");
        sb.append("    <Hidden>false</Hidden>\n");
        sb.append("    <RunOnlyIfIdle>false</RunOnlyIfIdle>\n");
        sb.append("    <WakeToRun>false</WakeToRun>\n");
        sb.append("    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\n");
        sb.append("    <Priority>7</Priority>\n");
        sb.append("  </Settings>\n");
        
        // Actions
        sb.append("  <Actions Context=\"Author\">\n");
        sb.append("    <Exec>\n");
        sb.append("      <Command>cmd.exe</Command>\n");
        sb.append("      <Arguments>/c \"").append(scriptPath).append("\"</Arguments>\n");
        sb.append("      <WorkingDirectory>").append(config.getWorkingDirectory()).append("</WorkingDirectory>\n");
        sb.append("    </Exec>\n");
        sb.append("  </Actions>\n");
        
        sb.append("</Task>\n");
        
        return sb.toString();
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
                new InputStreamReader(process.getInputStream(), "GBK"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
    
    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("windows");
    }
}