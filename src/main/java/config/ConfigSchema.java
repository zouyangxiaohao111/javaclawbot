package config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lark.oapi.service.search.v2.model.ModelConfig;
import context.BootstrapConfig;
import lombok.*;
import providers.ProviderRegistry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置结构定义（对应 Python 的 pydantic 模型）
 *
 * 说明：
 * - 读取 JSON 时：由 ConfigIO 负责“下划线键 -> 驼峰键”兼容
 * - 这里仅定义字段结构与提供者匹配逻辑
 */
public final class ConfigSchema {

    private ConfigSchema() {}

    // =========================
    // 渠道配置（字段与默认值一比一）
    // =========================























































}