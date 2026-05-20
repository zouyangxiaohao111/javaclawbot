package agent.tool.web;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * 搜索引擎策略接口。
 * WebSearchTool facade 通过此接口委托给具体引擎实现。
 */
public interface SearchEngine {

    /**
     * 执行搜索
     * @param query   搜索查询词
     * @param count   期望返回的结果数
     * @param args    完整的工具调用参数（引擎可从中提取额外参数）
     * @return 格式化后的搜索结果文本
     */
    CompletionStage<String> search(String query, int count, Map<String, Object> args);

    /**
     * 返回该引擎是否可用（API key 已配置或支持匿名访问）
     */
    boolean isAvailable();
}
