package config.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import config.provider.model.ModelConfig;
import config.provider.model.ModelConfig.ModelType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class ProvidersConfig {

    public ProvidersConfig() {
        initDefaultModelConfigs();
    }

    private void initDefaultModelConfigs() {
        // 初始化所有 ProviderConfig
        custom = new ProviderConfig("http://localhost:8000/v1");
        anthropic = new ProviderConfig("https://api.anthropic.com");
        openai = new ProviderConfig("https://api.openai.com/v1");
        openrouter = new ProviderConfig("https://openrouter.ai/api/v1");
        openrouter.setApiKey("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");
        deepseek = new ProviderConfig("https://api.deepseek.com");
        groq = new ProviderConfig("https://api.groq.com/openai/v1");
        zhipu = new ProviderConfig("https://open.bigmodel.cn/api/paas/v4");
        dashscope = new ProviderConfig("https://dashscope.aliyuncs.com/compatible-mode/v1");
        vllm = new ProviderConfig("http://localhost:8000/v1");
        gemini = new ProviderConfig("https://generativelanguage.googleapis.com/v1beta");
        moonshot = new ProviderConfig("https://api.moonshot.cn/v1");
        minimax = new ProviderConfig("https://api.minimax.chat/v1");
        aihubmix = new ProviderConfig("https://api.aihubmix.com/v1");
        siliconflow = new ProviderConfig("https://api.siliconflow.cn/v1");
        volcengine = new ProviderConfig("https://ark.cn-beijing.volces.com/api/v3");
        openaiCodex = new ProviderConfig("https://api.openai.com/v1");
        githubCopilot = new ProviderConfig("https://api.githubcopilot.com");

        // OpenAI - 2026年3月更新
        openai.setModelConfigs(List.of(
                model("gpt-4o", "gpt4o", ModelType.VISION, 16384, 64000),
                model("gpt-4o-mini", "gpt4o-mini", ModelType.CHAT, 16384, 64000),
                model("o1", "o1", ModelType.CHAT, 100000, 100000),
                model("o1-mini", "o1-mini", ModelType.CHAT, 65536, 65536),
                model("o3-mini", "o3-mini", ModelType.CHAT, 100000, 100000),
                model("gpt-4-turbo", "gpt4-turbo", ModelType.VISION, 4096, 64000),
                model("gpt-3.5-turbo", "gpt35", ModelType.CHAT, 4096, 64000)
        ));

        // Anthropic - 2026年3月更新 (基于官方SDK)
        anthropic.setModelConfigs(List.of(
                model("claude-opus-4-6", "claude-opus-4.6", ModelType.VISION, 32000, 64000),
                model("claude-sonnet-4-6", "claude-sonnet-4.6", ModelType.VISION, 64000, 64000),
                model("claude-haiku-4-5", "claude-haiku-4.5", ModelType.VISION, 8192, 64000),
                model("claude-opus-4-5", "claude-opus-4.5", ModelType.VISION, 32000, 64000),
                model("claude-sonnet-4-5", "claude-sonnet-4.5", ModelType.VISION, 64000, 64000),
                model("claude-opus-4-0", "claude-opus-4", ModelType.VISION, 16384, 64000),
                model("claude-sonnet-4-0", "claude-sonnet-4", ModelType.VISION, 64000, 64000),
                model("claude-3-5-sonnet-20241022", "claude-3.5-sonnet", ModelType.VISION, 8192, 64000),
                model("claude-3-5-haiku-20241022", "claude-3.5-haiku", ModelType.CHAT, 8192, 64000),
                model("claude-3-haiku-20240307", "claude-3-haiku", ModelType.CHAT, 4096, 64000)
        ));

        // DeepSeek - 2026年3月更新
        deepseek.setModelConfigs(List.of(
                model("deepseek-chat", "deepseek-chat", ModelType.CHAT, 8192, 64000),
                model("deepseek-reasoner", "deepseek-reasoner", ModelType.CHAT, 8192, 64000)
        ));

        // 智谱 GLM (zhipu) - 2026年3月更新 (基于官方文档)
        zhipu.setModelConfigs(List.of(
                model("glm-5", "glm-5", ModelType.CHAT, 131072, 64000),
                model("glm-5-turbo", "glm-5-turbo", ModelType.CHAT, 131072, 64000),
                model("glm-4.7", "glm-4.7", ModelType.CHAT, 131072, 64000),
                model("glm-4.7-flashx", "glm-4.7-flashx", ModelType.CHAT, 131072, 64000),
                model("glm-4.6", "glm-4.6", ModelType.CHAT, 131072, 64000),
                model("glm-4.5-air", "glm-4.5-air", ModelType.CHAT, 98304, 64000),
                model("glm-4.5-airx", "glm-4.5-airx", ModelType.CHAT, 98304, 64000),
                model("glm-4-long", "glm-4-long", ModelType.CHAT, 4096, 64000),
                model("glm-4.7-flash", "glm-4.7-flash", ModelType.CHAT, 131072, 64000),
                model("glm-4.5-flash", "glm-4.5-flash", ModelType.CHAT, 98304, 64000),
                model("glm-4-flash", "glm-4-flash", ModelType.CHAT, 16384, 64000),
                model("glm-4v-plus", "glm-4v", ModelType.VISION, 8192, 64000)
        ));

        // 阿里云 DashScope (通义千问) - 2026年3月更新 (基于官方文档)
        dashscope.setModelConfigs(List.of(
                model("qwen3-max", "qwen3-max", ModelType.CHAT, 32768, 64000),
                model("qwen3.5-plus", "qwen3.5-plus", ModelType.CHAT, 65536, 64000),
                model("qwen-plus", "qwen-plus", ModelType.CHAT, 32768, 64000),
                model("qwen-max", "qwen-max", ModelType.CHAT, 8192, 64000),
                model("qwen3.6-plus", "qwen3.6-plus", ModelType.VISION, 64000, 64000),
                model("qwen-vl-max", "qwen-vl", ModelType.VISION, 8192, 64000),
                model("qwen-long", "qwen-long", ModelType.CHAT, 8192, 1000000)
        ));

        // Groq - 2026年3月更新
        groq.setModelConfigs(List.of(
                model("llama-3.3-70b-versatile", "llama-3.3-70b", ModelType.CHAT, 8192, 64000),
                model("llama-3.1-8b-instant", "llama-3.1-8b", ModelType.CHAT, 8192, 64000),
                model("mixtral-8x7b-32768", "mixtral-8x7b", ModelType.CHAT, 32768, 64000)
        ));

        // Google Gemini - 2026年3月更新
        gemini.setModelConfigs(List.of(
                model("gemini-2.5-pro", "gemini-2.5-pro", ModelType.VISION, 65536, 64000),
                model("gemini-2.5-flash", "gemini-2.5-flash", ModelType.VISION, 65536, 64000),
                model("gemini-2.0-flash", "gemini-2.0-flash", ModelType.VISION, 8192, 64000),
                model("gemini-1.5-pro", "gemini-1.5-pro", ModelType.VISION, 8192, 64000),
                model("gemini-1.5-flash", "gemini-1.5-flash", ModelType.VISION, 8192, 64000)
        ));

        // Moonshot (月之暗面) - 2026年3月更新 (基于官方文档)
        moonshot.setModelConfigs(List.of(
                model("kimi-k2.5", "kimi-k2.5", ModelType.VISION, 131072, 64000),
                model("kimi-k2", "kimi-k2", ModelType.CHAT, 131072, 64000),
                model("moonshot-v1-8k", "moonshot-v1-8k", ModelType.CHAT, 8192, 64000),
                model("moonshot-v1-32k", "moonshot-v1-32k", ModelType.CHAT, 32768, 64000),
                model("moonshot-v1-128k", "moonshot-v1-128k", ModelType.CHAT, 131072, 64000)
        ));

        // MiniMax - 2026年3月更新
        minimax.setModelConfigs(List.of(
                model("abab6.5s-chat", "abab6.5s", ModelType.CHAT, 8192,245760),
                model("abab6.5g-chat", "abab6.5g", ModelType.CHAT, 8192,245760),
                model("abab6.5t-chat", "abab6.5t", ModelType.CHAT, 8192,245760)
        ));

        // SiliconFlow (硅基流动) - 2026年3月更新
        siliconflow.setModelConfigs(List.of(
                model("Qwen/Qwen2.5-72B-Instruct", "qwen2.5-72b", ModelType.CHAT, 8192, 64000),
                model("deepseek-ai/DeepSeek-V3", "deepseek-v3", ModelType.CHAT, 8192, 64000),
                model("meta-llama/Llama-3.3-70B-Instruct", "llama-3.3-70b", ModelType.CHAT, 8192, 64000)
        ));

        // OpenRouter - 2026年3月更新
        openrouter.setModelConfigs(List.of(
                model("qwen/qwen3.6-plus", "qwen/qwen3.6-plus", ModelType.TEXT, 8912, 64000),
                model("anthropic/claude-sonnet-4-6", "claude-sonnet-4.6", ModelType.TEXT, 16384, 64000),
                model("openai/gpt-4o", "gpt-4o", ModelType.TEXT, 16384),
                model("google/gemini-2.5-pro", "gemini-2.5-pro", ModelType.TEXT, 16384, 64000),
                model("google/gemini-2.5-pro", "gemini-2.5-pro", ModelType.VISION, 16384, 64000),
                model("google/gemini-2.0-flash-exp:free", "gemini-2.0-flash-free", ModelType.VISION, 16384, 64000)
        ));

        // 火山引擎 - 2026年3月更新
        volcengine.setModelConfigs(List.of(
                model("doubao-pro-32k", "doubao-pro-32k", ModelType.CHAT, 32768, 64000),
                model("doubao-pro-128k", "doubao-pro-128k", ModelType.CHAT, 131072, 64000),
                model("doubao-lite-32k", "doubao-lite-32k", ModelType.CHAT, 32768, 64000)
        ));

        // AIHubMix - 2026年3月更新
        aihubmix.setModelConfigs(List.of(
                model("gpt-4o", "gpt-4o", ModelType.VISION, 16384, 64000),
                model("claude-opus-4-6", "claude-opus-4.6", ModelType.VISION, 8912, 64000),
                model("claude-sonnet-4-6", "claude-sonnet-4.6", ModelType.VISION, 8912, 64000),
                model("gemini-2.5-pro", "gemini-2.5-pro", ModelType.VISION, 8912, 64000),
                model("gemini-2.0-flash", "gemini-2.0-flash", ModelType.VISION, 8192, 64000)
        ));
    }

    private static ModelConfig model(String model, String alias, ModelType type, int maxTokens) {
        ModelConfig config = new ModelConfig();
        config.setModel(model);
        config.setAlias(alias);
        config.setType(type);
        config.setMaxTokens(maxTokens);
        return config;
    }

    private static ModelConfig model(String model, String alias, ModelType type, int maxTokens, int contextWindow) {
        ModelConfig config = new ModelConfig();
        config.setModel(model);
        config.setAlias(alias);
        config.setType(type);
        config.setMaxTokens(maxTokens);
        config.setContextWindow(contextWindow);
        return config;
    }

    /**
     * 自定义模型
     */
    private ProviderConfig custom;
    private ProviderConfig anthropic;
    private ProviderConfig openai;
    private ProviderConfig openrouter;
    private ProviderConfig deepseek;
    private ProviderConfig groq;
    private ProviderConfig zhipu;
    private ProviderConfig dashscope;
    private ProviderConfig vllm;
    private ProviderConfig gemini;
    private ProviderConfig moonshot;
    private ProviderConfig minimax;
    private ProviderConfig aihubmix;
    private ProviderConfig siliconflow;
    private ProviderConfig volcengine;
    private ProviderConfig openaiCodex;
    private ProviderConfig githubCopilot;

    public ProviderConfig getByName(String name) {
        if (name == null) return custom;
        return switch (name) {
            case "custom" -> custom;
            case "anthropic" -> anthropic;
            case "openai" -> openai;
            case "openrouter" -> openrouter;
            case "deepseek" -> deepseek;
            case "groq" -> groq;
            case "zhipu" -> zhipu;
            case "dashscope" -> dashscope;
            case "vllm" -> vllm;
            case "gemini" -> gemini;
            case "moonshot" -> moonshot;
            case "minimax" -> minimax;
            case "aihubmix" -> aihubmix;
            case "siliconflow" -> siliconflow;
            case "volcengine" -> volcengine;
            case "openai_codex", "openaiCodex" -> openaiCodex;
            case "github_copilot", "githubCopilot" -> githubCopilot;
            default -> null;
        };
    }
}