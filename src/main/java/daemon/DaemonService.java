package daemon;

import java.nio.file.Path;
import java.util.List;

/**
 * 守护进程服务接口
 * 
 * 对齐 OpenClaw 的 daemon/service.ts
 */
public interface DaemonService {
    
    /**
     * 安装服务
     */
    ServiceResult install(ServiceConfig config);
    
    /**
     * 卸载服务
     */
    ServiceResult uninstall();
    
    /**
     * 启动服务
     */
    ServiceResult start();
    
    /**
     * 停止服务
     */
    ServiceResult stop();
    
    /**
     * 重启服务
     */
    ServiceResult restart();
    
    /**
     * 查看服务状态
     */
    ServiceStatus status();
    
    /**
     * 是否已安装
     */
    boolean isInstalled();
    
    /**
     * 是否正在运行
     */
    boolean isRunning();
    
    /**
     * 获取支持的平台名称
     */
    String getPlatform();
    
    /**
     * 获取服务标签（用于显示）
     */
    String getLabel();
    
    /**
     * 服务配置
     */
    record ServiceConfig(
        int port,
        String workspace,
        String configPath,
        String javaHome,
        String jarPath,
        List<String> extraArgs
    ) {
        public ServiceConfig(int port) {
            this(port, null, null, 
                System.getProperty("java.home"),
                detectJarPath(),
                List.of());
        }
        
        public ServiceConfig(int port, String workspace) {
            this(port, workspace, null,
                System.getProperty("java.home"),
                detectJarPath(),
                List.of());
        }
        
        private static String detectJarPath() {
            // 尝试检测当前运行的 jar 路径
            String classPath = System.getProperty("java.class.path");
            if (classPath != null && classPath.endsWith(".jar")) {
                return classPath;
            }
            // 默认路径
            String home = System.getProperty("user.home");
            return home + "/.javaclawbot/javaclawbot.jar";
        }
        
        public String getExecStart() {
            StringBuilder sb = new StringBuilder();
            sb.append(javaHome != null ? javaHome + "/bin/java" : "java");
            sb.append(" -jar ").append(jarPath);
            sb.append(" gateway --port ").append(port);
            if (workspace != null && !workspace.isBlank()) {
                sb.append(" --workspace ").append(workspace);
            }
            if (configPath != null && !configPath.isBlank()) {
                sb.append(" --config ").append(configPath);
            }
            for (String arg : extraArgs) {
                sb.append(" ").append(arg);
            }
            return sb.toString();
        }
        
        public String getWorkingDirectory() {
            return workspace != null ? workspace : System.getProperty("user.home");
        }
    }
    
    /**
     * 服务操作结果
     */
    record ServiceResult(
        boolean success,
        String message,
        String error
    ) {
        public static ServiceResult success(String message) {
            return new ServiceResult(true, message, null);
        }
        
        public static ServiceResult failure(String error) {
            return new ServiceResult(false, null, error);
        }
        
        public static ServiceResult failure(String message, String error) {
            return new ServiceResult(false, message, error);
        }
    }
    
    /**
     * 服务状态
     */
    record ServiceStatus(
        boolean installed,
        boolean running,
        String statusText,
        String pid,
        String uptime,
        String logPath
    ) {
        public static ServiceStatus notInstalled() {
            return new ServiceStatus(false, false, "未安装", null, null, null);
        }
        
        public static ServiceStatus stopped() {
            return new ServiceStatus(true, false, "已停止", null, null, null);
        }
        
        public static ServiceStatus running(String pid) {
            return new ServiceStatus(true, true, "运行中", pid, null, null);
        }
    }
}