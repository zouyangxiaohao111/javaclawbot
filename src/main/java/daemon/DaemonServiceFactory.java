package daemon;

/**
 * 守护进程服务工厂
 * 
 * 根据操作系统自动选择合适的服务实现
 */
public class DaemonServiceFactory {
    
    /**
     * 创建适合当前系统的服务实现
     */
    public static DaemonService create() {
        String os = System.getProperty("os.name", "").toLowerCase();
        
        if (os.contains("linux")) {
            return new SystemdService();
        } else if (os.contains("mac") || os.contains("darwin")) {
            return new LaunchdService();
        } else if (os.contains("windows")) {
            return new SchtasksService();
        }
        
        throw new UnsupportedOperationException("不支持的操作系统: " + os);
    }
    
    /**
     * 获取当前平台名称
     */
    public static String getCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        
        if (os.contains("linux")) {
            return "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        } else if (os.contains("windows")) {
            return "windows";
        }
        
        return "unknown";
    }
    
    /**
     * 获取当前平台的服务标签
     */
    public static String getCurrentServiceLabel() {
        return create().getLabel();
    }
    
    /**
     * 检查当前系统是否支持服务安装
     */
    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("mac") || 
               os.contains("darwin") || os.contains("windows");
    }
}