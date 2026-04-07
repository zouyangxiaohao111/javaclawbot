package providers.cli;

import providers.cli.model.CliSessionInfo;
import providers.cli.model.ModelOption;
import providers.cli.model.PermissionModeInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CLI Agent 接口
 */
public interface CliAgent {

    /**
     * Agent 名称
     */
    String name();

    /**
     * CLI 二进制名称
     */
    String cliBinaryName();

    /**
     * 检查 CLI 是否可用
     */
    boolean checkCliAvailable();

    /**
     * 创建会话
     */
    CompletableFuture<CliAgentSession> createSession(CliAgentConfig config);

    /**
     * 列出后端会话
     */
    CompletableFuture<List<CliSessionInfo>> listSessions(String workDir);

    /**
     * 获取可用模型列表
     */
    List<ModelOption> getAvailableModels();

    /**
     * 获取权限模式列表
     */
    List<PermissionModeInfo> getPermissionModes();

    /**
     * 获取项目内存文件路径
     */
    String getProjectMemoryFile(String workDir);
}
