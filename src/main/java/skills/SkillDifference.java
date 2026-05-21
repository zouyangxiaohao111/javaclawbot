package skills;

/**
 * 技能差异数据模型
 */
public class SkillDifference {

    public enum DifferenceType {
        NEW,       // 工作空间中不存在，需要新增
        MODIFIED   // 内容不同，需要覆盖
    }

    private final String skillName;
    private final String builtinHash;
    private final String workspaceHash;
    private final DifferenceType type;

    public SkillDifference(String skillName, String builtinHash, String workspaceHash, DifferenceType type) {
        this.skillName = skillName;
        this.builtinHash = builtinHash;
        this.workspaceHash = workspaceHash;
        this.type = type;
    }

    public String getSkillName() { return skillName; }
    public String getBuiltinHash() { return builtinHash; }
    public String getWorkspaceHash() { return workspaceHash; }
    public DifferenceType getType() { return type; }

    public String getTypeLabel() {
        return switch (type) {
            case NEW -> "新增";
            case MODIFIED -> "已修改";
        };
    }
}
