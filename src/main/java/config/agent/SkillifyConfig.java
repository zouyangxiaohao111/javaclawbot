package config.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Skillify Configuration
 *
 * 配置参数：
 * - enabled: 总开关
 * - requireSessionMemory: 是否强依赖 Session Memory（默认 true）
 *
 * TODO: disableModelInvocation 对齐
 * 源码路径: Open-ClaudeCode/src/skills/bundled/skillify.ts
 * Open-ClaudeCode 使用 disableModelInvocation: true，表示不调用 LLM，
 * 直接返回 prompt 给用户通过 AskUserQuestion 交互式创建 skill。
 * 当前 Java 实现使用 LLM 生成 skill 内容，与 Open-ClaudeCode 不一致。
 * 等价实现应改为：
 * 1. disableModelInvocation: true - 不调用 LLM
 * 2. 使用 AskUserQuestion 引导用户交互式创建
 * 3. 直接返回 prompt，让用户通过对话完成 skill 创建
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillifyConfig {

    /**
     * 是否启用 Skillify
     */
    private boolean enabled = false;

    /**
     * 是否强依赖 Session Memory
     * true: 必须开启 Session Memory 才能使用（默认 true，因为没有 session memory 质量差）
     * false: 可选
     */
    private boolean requireSessionMemory = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireSessionMemory() {
        return requireSessionMemory;
    }

    public void setRequireSessionMemory(boolean requireSessionMemory) {
        this.requireSessionMemory = requireSessionMemory;
    }

    /**
     * 检查是否强依赖 Session Memory
     */
    public boolean shouldRequireSessionMemory() {
        return requireSessionMemory;
    }
}
