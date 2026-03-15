package agent.subagent;

import java.util.concurrent.CompletionStage;

/**
 * 子Agent执行器接口
 *
 * 定义子Agent的执行契约，允许不同的实现方式：
 * - 本地执行（当前进程内）
 * - 远程执行（通过Gateway）
 * - ACP Harness执行
 */
public interface SubagentExecutor {

    /**
     * 执行子Agent任务
     *
     * @param record       运行记录
     * @param systemPrompt 系统提示词
     * @return 执行结果
     */
    CompletionStage<SubagentExecutionResult> execute(SubagentRunRecord record, String systemPrompt);

    /**
     * 检查子Agent是否正在运行
     *
     * @param runId 运行ID
     * @return 是否正在运行
     */
    boolean isRunning(String runId);

    /**
     * 终止子Agent运行
     *
     * @param runId 运行ID
     * @return 是否成功终止
     */
    CompletionStage<Boolean> terminate(String runId);
}