package session;

import config.agent.SessionMemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Session Memory Utility Functions
 * 对齐 Open-ClaudeCode: src/services/SessionMemory/sessionMemoryUtils.ts
 *
 * 关键常量：
 * - EXTRACTION_WAIT_TIMEOUT_MS: 15000 (15秒)
 * - EXTRACTION_STALE_THRESHOLD_MS: 60000 (1分钟)
 */
public class SessionMemoryUtils {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryUtils.class);

    /**
     * 等待提取完成的超时时间（毫秒）
     * 对齐: EXTRACTION_WAIT_TIMEOUT_MS = 15000
     */
    public static final long EXTRACTION_WAIT_TIMEOUT_MS = 15_000;

    /**
     * 提取进程超时阈值（毫秒）
     * 对齐: EXTRACTION_STALE_THRESHOLD_MS = 60000
     */
    public static final long EXTRACTION_STALE_THRESHOLD_MS = 60_000;

    /**
     * Session Memory 目录名
     * 对齐 Open-ClaudeCode: {projectDir}/{sessionId}/session-memory/summary.md
     * 注意：Open-ClaudeCode 使用 hyphen (session-memory)，不是 underscore
     */
    private static final String SESSION_MEMORY_DIR = "sessions";
    private static final String SESSION_MEMORY_SUBDIR = "session-memory";  // 对齐 Open-ClaudeCode

    /**
     * 获取 Session Memory 文件路径
     * 对齐 Open-ClaudeCode: getSessionMemoryPath() in src/utils/permissions/filesystem.ts:269-271
     * 路径格式: {workspace}/sessions/{sessionId}/session-memory/summary.md
     */
    public static Path getSessionMemoryPath(String sessionId, Path workspace) {
        return workspace.resolve(SESSION_MEMORY_DIR)
                .resolve(sessionId)
                .resolve(SESSION_MEMORY_SUBDIR)
                .resolve("summary.md");
    }

    /**
     * 获取 Session Memory 目录路径
     * 对齐 Open-ClaudeCode: getSessionMemoryDir() in src/utils/permissions/filesystem.ts:261-263
     */
    public static Path getSessionMemoryDir(String sessionId, Path workspace) {
        return workspace.resolve(SESSION_MEMORY_DIR)
                .resolve(sessionId)
                .resolve(SESSION_MEMORY_SUBDIR);
    }

    /**
     * 读取 Session Memory 内容
     * 对齐: getSessionMemoryContent() in sessionMemoryUtils.ts:110-126
     */
    public static String readSessionMemory(Path memoryPath) {
        if (!Files.exists(memoryPath)) {
            return null;
        }
        try {
            return Files.readString(memoryPath);
        } catch (Exception e) {
            log.warn("Failed to read session memory: {}", memoryPath, e);
            return null;
        }
    }

    /**
     * 写入 Session Memory 内容
     */
    public static boolean writeSessionMemory(Path memoryPath, String content) {
        try {
            Files.createDirectories(memoryPath.getParent());
            Files.writeString(memoryPath, content);
            return true;
        } catch (Exception e) {
            log.warn("Failed to write session memory: {}", memoryPath, e);
            return false;
        }
    }

    /**
     * 检查 Session Memory 是否为空模板
     * 对齐: isSessionMemoryEmpty() in prompts.ts
     */
    public static boolean isSessionMemoryEmpty(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        // 检查是否只有模板结构没有实际内容
        // 简单判断：内容中是否有实际段落（非空白、非标题、非斜体描述）
        String stripped = content
                .replaceAll("(?m)^#.*$", "")           // 移除标题
                .replaceAll("(?m)^_.*_$", "")          // 移除斜体描述
                .replaceAll("(?m)^\\s*$", "")          // 移除空行
                .trim();
        return stripped.isEmpty();
    }

    /**
     * 追踪状态：上次提取时的 token 数
     */
    private static int tokensAtLastExtraction = 0;

    /**
     * 追踪状态：是否已初始化
     */
    private static boolean sessionMemoryInitialized = false;

    /**
     * 追踪状态：最后一次摘要的消息 UUID
     */
    private static String lastSummarizedMessageId;

    /**
     * 追踪状态：提取开始时间
     */
    private static Long extractionStartedAt;

    /**
     * 追踪状态：工具调用次数
     */
    private static int toolCallsSinceLastExtraction = 0;

    /**
     * 检查是否已初始化
     */
    public static boolean isSessionMemoryInitialized() {
        return sessionMemoryInitialized;
    }

    /**
     * 标记已初始化
     */
    public static void markSessionMemoryInitialized() {
        sessionMemoryInitialized = true;
    }

    /**
     * 获取最后一次摘要的消息 ID
     */
    public static String getLastSummarizedMessageId() {
        return lastSummarizedMessageId;
    }

    /**
     * 设置最后一次摘要的消息 ID
     */
    public static void setLastSummarizedMessageId(String messageId) {
        lastSummarizedMessageId = messageId;
    }

    /**
     * 记录提取时的 token 数
     */
    public static void recordExtractionTokenCount(int tokenCount) {
        tokensAtLastExtraction = tokenCount;
    }

    /**
     * 获取上次提取时的 token 数
     */
    public static int getTokensAtLastExtraction() {
        return tokensAtLastExtraction;
    }

    /**
     * 检查是否满足初始化阈值
     * 对齐: hasMetInitializationThreshold() in sessionMemoryUtils.ts:173-177
     */
    public static boolean hasMetInitializationThreshold(int currentTokenCount, SessionMemoryConfig config) {
        return currentTokenCount >= config.getMinimumMessageTokensToInit();
    }

    /**
     * 检查是否满足更新阈值
     * 对齐: hasMetUpdateThreshold() in sessionMemoryUtils.ts:184-189
     */
    public static boolean hasMetUpdateThreshold(int currentTokenCount, SessionMemoryConfig config) {
        int tokensSinceLastExtraction = currentTokenCount - tokensAtLastExtraction;
        return tokensSinceLastExtraction >= config.getMinimumTokensBetweenUpdate();
    }

    /**
     * 增加工具调用计数
     */
    public static void incrementToolCallsSinceLastExtraction() {
        toolCallsSinceLastExtraction++;
    }

    /**
     * 重置工具调用计数
     */
    public static void resetToolCallsSinceLastExtraction() {
        toolCallsSinceLastExtraction = 0;
    }

    /**
     * 获取工具调用计数
     */
    public static int getToolCallsSinceLastExtraction() {
        return toolCallsSinceLastExtraction;
    }

    /**
     * 检查工具调用阈值
     */
    public static boolean hasMetToolCallThreshold(SessionMemoryConfig config) {
        return toolCallsSinceLastExtraction >= config.getToolCallsBetweenUpdates();
    }

    /**
     * 标记提取开始
     */
    public static void markExtractionStarted() {
        extractionStartedAt = System.currentTimeMillis();
    }

    /**
     * 标记提取完成
     */
    public static void markExtractionCompleted() {
        extractionStartedAt = null;
    }

    /**
     * 检查提取是否进行中
     */
    public static boolean isExtractionInProgress() {
        return extractionStartedAt != null;
    }

    /**
     * 检查提取是否过期（超过阈值）
     */
    public static boolean isExtractionStale() {
        if (extractionStartedAt == null) {
            return false;
        }
        return (System.currentTimeMillis() - extractionStartedAt) > EXTRACTION_STALE_THRESHOLD_MS;
    }

    /**
     * 等待提取完成
     * 对齐: waitForSessionMemoryExtraction() in sessionMemoryUtils.ts:89-105
     */
    public static void waitForExtraction() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (isExtractionInProgress()) {
            if (isExtractionStale()) {
                // 提取过期，不再等待
                return;
            }
            if ((System.currentTimeMillis() - startTime) > EXTRACTION_WAIT_TIMEOUT_MS) {
                // 超时，不再等待
                return;
            }
            Thread.sleep(1000);
        }
    }

    /**
     * 重置所有状态
     */
    public static void reset() {
        tokensAtLastExtraction = 0;
        sessionMemoryInitialized = false;
        lastSummarizedMessageId = null;
        extractionStartedAt = null;
        toolCallsSinceLastExtraction = 0;
    }
}
