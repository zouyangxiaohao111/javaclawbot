package agent.subagent;

/**
 * 子Agent模块公共工具方法。
 */
public final class SubagentUtils {

    private SubagentUtils() {}

    /**
     * 截断字符串，超出部分用 "..." 替代。
     *
     * @param s   原始字符串，null 返回空字符串
     * @param max 最大保留长度
     * @return 截断后的字符串
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 截断文本，超出部分用 "... [truncated]" 替代。
     *
     * @param text      原始文本，null 返回 null
     * @param maxLength 最大保留长度
     * @return 截断后的文本
     */
    public static String truncateText(String text, int maxLength) {
        if (text == null) return null;
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }
}
