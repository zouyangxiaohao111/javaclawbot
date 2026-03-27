package config.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class ProvidersConfig {
    private ProviderConfig custom = new ProviderConfig("http://localhost:8000/v1");

    private ProviderConfig anthropic =
            new ProviderConfig("https://api.anthropic.com");

    private ProviderConfig openai =
            new ProviderConfig("https://api.openai.com/v1");

    private ProviderConfig openrouter =
            new ProviderConfig("https://openrouter.ai/api/v1");

    private ProviderConfig deepseek =
            new ProviderConfig("https://api.deepseek.com");

    private ProviderConfig groq =
            new ProviderConfig("https://api.groq.com/openai/v1");

    private ProviderConfig zhipu =
            new ProviderConfig("https://open.bigmodel.cn/api/paas/v4");

    private ProviderConfig dashscope =
            new ProviderConfig("https://dashscope.aliyuncs.com/compatible-mode/v1");

    private ProviderConfig vllm =
            new ProviderConfig("http://localhost:8000/v1");

    private ProviderConfig gemini =
            new ProviderConfig("https://generativelanguage.googleapis.com/v1beta");

    private ProviderConfig moonshot =
            new ProviderConfig("https://api.moonshot.cn/v1");

    private ProviderConfig minimax =
            new ProviderConfig("https://api.minimax.chat/v1");

    private ProviderConfig aihubmix =
            new ProviderConfig("https://api.aihubmix.com/v1");

    private ProviderConfig siliconflow =
            new ProviderConfig("https://api.siliconflow.cn/v1");

    private ProviderConfig volcengine =
            new ProviderConfig("https://ark.cn-beijing.volces.com/api/v3");

    private ProviderConfig openaiCodex =
            new ProviderConfig("https://api.openai.com/v1");

    private ProviderConfig githubCopilot =
            new ProviderConfig("https://api.githubcopilot.com");

    public ProviderConfig getByName(String name) {
        if (name == null) return null;
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