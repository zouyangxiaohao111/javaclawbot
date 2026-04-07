package providers.cli.model;

import java.util.List;

/**
 * 用户问题
 *
 * 复刻 cc-connect core/interfaces.go UserQuestion
 * 用于 AskUserQuestion 工具的结构化问题
 */
public record UserQuestion(
    String question,
    String header,
    List<UserQuestionOption> options,
    boolean multiSelect
) {
    public UserQuestion {
        // 允许 null
    }
}
