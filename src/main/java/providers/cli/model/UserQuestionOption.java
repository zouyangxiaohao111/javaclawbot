package providers.cli.model;

/**
 * 用户问题选项
 *
 * 复刻 cc-connect core/interfaces.go UserQuestionOption
 */
public record UserQuestionOption(
    String label,
    String description
) {
    public UserQuestionOption {
        // 允许 null
    }
}
