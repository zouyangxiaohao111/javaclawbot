package cli;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 终端交互工具
 *
 * 提供：
 * - 文本输入
 * - 密码输入
 * - confirm
 * - 单选
 * - 多选（空格勾选）
 *
 * 说明：
 * - 使用 JLine 的 BindingReader + KeyMap 做按键绑定，
 *   不再手动解析 ESC 序列，兼容性更好
 * - 单选支持：
 *   - ↑/↓ 或 j/k 移动
 *   - Enter 确认当前项
 *   - 数字 + Enter 直接按编号选择
 *   - s/q 跳过（若 allowSkip=true）
 * - 多选支持：
 *   - ↑/↓ 或 j/k 移动
 *   - Space 切换当前项
 *   - 数字 + Enter 切换指定编号
 *   - a 全选 / 取消全选
 *   - Enter 确认
 *   - s/q 跳过（若 allowSkip=true）
 */
public final class TerminalPrompts {

    private TerminalPrompts() {}

    public static final class SelectionResult<T> {
        private final boolean skipped;
        private final List<T> items;

        public SelectionResult(boolean skipped, List<T> items) {
            this.skipped = skipped;
            this.items = (items != null) ? items : List.of();
        }

        public boolean isSkipped() { return skipped; }
        public List<T> getItems() { return items; }
    }

    public interface LabelProvider<T> {
        String labelOf(T item);
    }

    /**
     * 普通文本输入
     *
     * 行为：
     * - 直接回车：保留 currentValue
     * - 输入新值：返回新值
     */
    public static String promptText(LineReader reader, String prompt, String currentValue) {
        String suffix = (currentValue != null && !currentValue.isBlank())
                ? " [回车保留当前值: " + currentValue + "]"
                : "";
        try {
            String s = reader.readLine(prompt + suffix + ": ");
            if (s == null || s.trim().isEmpty()) return currentValue;
            return s.trim();
        } catch (UserInterruptException e) {
            return currentValue;
        }
    }

    /**
     * 密码输入
     *
     * 行为：
     * - 直接回车：保留 currentValue
     * - 输入新值：返回新值
     */
    public static String promptSecret(LineReader reader, String prompt, String currentValue) {
        String suffix = (currentValue != null && !currentValue.isBlank())
                ? " [回车保留当前值]"
                : "";
        try {
            String s = reader.readLine(prompt + suffix + ": ", '*');
            if (s == null || s.trim().isEmpty()) return currentValue;
            return s.trim();
        } catch (UserInterruptException e) {
            return currentValue;
        }
    }

    /**
     * 确认输入
     */
    public static boolean promptConfirm(LineReader reader, String prompt, boolean defaultYes) {
        String tail = defaultYes ? " [Y/n] " : " [y/N] ";
        try {
            String s = reader.readLine(prompt + tail);
            if (s == null || s.trim().isEmpty()) return defaultYes;
            s = s.trim().toLowerCase(Locale.ROOT);
            return s.equals("y") || s.equals("yes");
        } catch (UserInterruptException e) {
            return defaultYes;
        }
    }

    /**
     * 单选
     */
    public static <T> T singleSelect(
            Terminal terminal,
            List<T> items,
            LabelProvider<T> labelProvider,
            String title,
            int defaultIndex,
            boolean allowSkip
    ) {
        if (items == null || items.isEmpty()) return null;

        int cursor = Math.max(0, Math.min(defaultIndex, items.size() - 1));
        StringBuilder numberBuffer = new StringBuilder();

        BindingReader bindingReader = new BindingReader(terminal.reader());
        KeyMap<String> keyMap = buildSingleSelectKeyMap(terminal, allowSkip);

        try {
            terminal.enterRawMode();
            enterAltScreen(terminal);

            while (true) {
                renderSingle(
                        terminal,
                        items,
                        labelProvider,
                        title,
                        cursor,
                        allowSkip,
                        numberBuffer.toString()
                );

                String op = bindingReader.readBinding(keyMap);
                if (op == null) return null;

                switch (op) {
                    case "up" -> {
                        cursor = Math.max(0, cursor - 1);
                        numberBuffer.setLength(0);
                    }
                    case "down" -> {
                        cursor = Math.min(items.size() - 1, cursor + 1);
                        numberBuffer.setLength(0);
                    }
                    case "accept" -> {
                        if (numberBuffer.length() > 0) {
                            try {
                                int n = Integer.parseInt(numberBuffer.toString());
                                if (n >= 1 && n <= items.size()) {
                                    return items.get(n - 1);
                                }
                            } catch (Exception ignored) {
                            }
                            numberBuffer.setLength(0);
                        } else {
                            return items.get(cursor);
                        }
                    }
                    case "skip" -> {
                        return null;
                    }
                    case "backspace" -> {
                        if (numberBuffer.length() > 0) {
                            numberBuffer.setLength(numberBuffer.length() - 1);
                        }
                    }
                    default -> {
                        if (op.startsWith("digit:")) {
                            numberBuffer.append(op.substring("digit:".length()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("\nSelector failed: " + e.getMessage());
            return null;
        } finally {
            exitAltScreen(terminal);
        }
    }

    /**
     * 多选
     */
    public static <T> SelectionResult<T> multiSelect(
            Terminal terminal,
            List<T> items,
            LabelProvider<T> labelProvider,
            String title,
            boolean preselectAll,
            boolean allowSkip
    ) {
        if (items == null || items.isEmpty()) {
            return new SelectionResult<>(true, List.of());
        }

        Set<Integer> selected = new LinkedHashSet<>();
        if (preselectAll) {
            for (int i = 0; i < items.size(); i++) {
                selected.add(i);
            }
        }

        int cursor = 0;
        StringBuilder numberBuffer = new StringBuilder();

        BindingReader bindingReader = new BindingReader(terminal.reader());
        KeyMap<String> keyMap = buildMultiSelectKeyMap(terminal, allowSkip);

        try {
            terminal.enterRawMode();
            enterAltScreen(terminal);

            while (true) {
                renderMulti(
                        terminal,
                        items,
                        labelProvider,
                        title,
                        selected,
                        cursor,
                        allowSkip,
                        numberBuffer.toString()
                );

                String op = bindingReader.readBinding(keyMap);
                if (op == null) return new SelectionResult<>(true, List.of());

                switch (op) {
                    case "up" -> {
                        cursor = Math.max(0, cursor - 1);
                        numberBuffer.setLength(0);
                    }
                    case "down" -> {
                        cursor = Math.min(items.size() - 1, cursor + 1);
                        numberBuffer.setLength(0);
                    }
                    case "toggle" -> {
                        if (selected.contains(cursor)) selected.remove(cursor);
                        else selected.add(cursor);
                        numberBuffer.setLength(0);
                    }
                    case "all" -> {
                        if (selected.size() == items.size()) {
                            selected.clear();
                        } else {
                            selected.clear();
                            for (int i = 0; i < items.size(); i++) {
                                selected.add(i);
                            }
                        }
                        numberBuffer.setLength(0);
                    }
                    case "accept" -> {
                        if (numberBuffer.length() > 0) {
                            try {
                                int n = Integer.parseInt(numberBuffer.toString());
                                if (n >= 1 && n <= items.size()) {
                                    int idx = n - 1;
                                    if (selected.contains(idx)) selected.remove(idx);
                                    else selected.add(idx);
                                    cursor = idx;
                                }
                            } catch (Exception ignored) {
                            }
                            numberBuffer.setLength(0);
                        } else {
                            List<T> out = selected.stream()
                                    .sorted()
                                    .map(items::get)
                                    .collect(Collectors.toList());
                            return new SelectionResult<>(false, out);
                        }
                    }
                    case "skip" -> {
                        return new SelectionResult<>(true, List.of());
                    }
                    case "backspace" -> {
                        if (numberBuffer.length() > 0) {
                            numberBuffer.setLength(numberBuffer.length() - 1);
                        }
                    }
                    default -> {
                        if (op.startsWith("digit:")) {
                            numberBuffer.append(op.substring("digit:".length()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("\nSelector failed: " + e.getMessage());
            return new SelectionResult<>(true, List.of());
        } finally {
            exitAltScreen(terminal);
        }
    }

    /**
     * 构建单选键盘映射
     */
    private static KeyMap<String> buildSingleSelectKeyMap(Terminal terminal, boolean allowSkip) {
        KeyMap<String> keyMap = new KeyMap<>();

        // 上下移动 - 使用 JLine 内置键序列，兼容性更好
        keyMap.bind("up", KeyMap.key(terminal, InfoCmp.Capability.key_up), "k", "K");
        keyMap.bind("down", KeyMap.key(terminal, InfoCmp.Capability.key_down), "j", "J");

        // 兼容 Ctrl-P / Ctrl-N
        keyMap.bind("up", KeyMap.ctrl('P'));
        keyMap.bind("down", KeyMap.ctrl('N'));

        // 确认
        keyMap.bind("accept", "\r", "\n");

        // 跳过
        if (allowSkip) {
            keyMap.bind("skip", "s", "S", "q", "Q");
        }

        // 退格
        keyMap.bind("backspace", KeyMap.del(), "\b", "\177");

        // 数字输入
        for (char c = '0'; c <= '9'; c++) {
            keyMap.bind("digit:" + c, String.valueOf(c));
        }

        return keyMap;
    }

    /**
     * 构建多选键盘映射
     */
    private static KeyMap<String> buildMultiSelectKeyMap(Terminal terminal, boolean allowSkip) {
        KeyMap<String> keyMap = new KeyMap<>();

        // 上下移动 - 使用 JLine 内置键序列，兼容性更好
        keyMap.bind("up", KeyMap.key(terminal, InfoCmp.Capability.key_up), "k", "K");
        keyMap.bind("down", KeyMap.key(terminal, InfoCmp.Capability.key_down), "j", "J");

        // 兼容 Ctrl-P / Ctrl-N
        keyMap.bind("up", KeyMap.ctrl('P'));
        keyMap.bind("down", KeyMap.ctrl('N'));

        // 确认
        keyMap.bind("accept", "\r", "\n");

        // 切换当前项
        keyMap.bind("toggle", " ");

        // 全选/取消全选
        keyMap.bind("all", "a", "A");

        // 跳过
        if (allowSkip) {
            keyMap.bind("skip", "s", "S", "q", "Q");
        }

        // 退格
        keyMap.bind("backspace", KeyMap.del(), "\b", "\177");

        // 数字输入
        for (char c = '0'; c <= '9'; c++) {
            keyMap.bind("digit:" + c, String.valueOf(c));
        }

        return keyMap;
    }

    /**
     * 渲染单选界面
     */
    private static void renderSingle(
            Terminal terminal,
            List<?> items,
            LabelProvider<?> labelProvider,
            String title,
            int cursor,
            boolean allowSkip,
            String numberBuffer
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001b[H\u001b[2J");
        sb.append(title).append("\n");
        sb.append("方向键: ↑/↓ 或者 j/k 移动, 回车确认, 数字 + 回车 直接选择");
        if (allowSkip) sb.append(", s skip");
        sb.append("\n\n");

        for (int i = 0; i < items.size(); i++) {
            String p = (i == cursor) ? ">" : " ";
            @SuppressWarnings("unchecked")
            String label = ((LabelProvider<Object>) labelProvider).labelOf(items.get(i));
            sb.append(p).append(" ").append(i + 1).append(". ").append(label).append("\n");
        }

        if (numberBuffer != null && !numberBuffer.isEmpty()) {
            sb.append("\n当前编号输入: ").append(numberBuffer).append("\n");
        }

        terminal.writer().print(sb);
        terminal.flush();
    }

    /**
     * 渲染多选界面
     */
    private static void renderMulti(
            Terminal terminal,
            List<?> items,
            LabelProvider<?> labelProvider,
            String title,
            Set<Integer> selected,
            int cursor,
            boolean allowSkip,
            String numberBuffer
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001b[H\u001b[2J");
        sb.append(title).append("\n");
        sb.append("Controls: ↑/↓ or j/k move, Space toggle, 数字 + Enter 切换, a all, Enter confirm");
        if (allowSkip) sb.append(", s skip");
        sb.append("\n\n");

        for (int i = 0; i < items.size(); i++) {
            String p = (i == cursor) ? ">" : " ";
            String m = selected.contains(i) ? "[x]" : "[ ]";
            @SuppressWarnings("unchecked")
            String label = ((LabelProvider<Object>) labelProvider).labelOf(items.get(i));
            sb.append(p).append(" ").append(i + 1).append(". ").append(m).append(" ").append(label).append("\n");
        }

        if (numberBuffer != null && !numberBuffer.isEmpty()) {
            sb.append("\n当前编号输入: ").append(numberBuffer).append("（按 Enter 切换该项）\n");
        }

        sb.append("\nSelected: ").append(selected.size()).append("/").append(items.size()).append("\n");

        terminal.writer().print(sb);
        terminal.flush();
    }

    /**
     * 进入备用屏幕并清屏
     */
    private static void enterAltScreen(Terminal terminal) {
        try {
            terminal.puts(InfoCmp.Capability.enter_ca_mode);
            terminal.puts(InfoCmp.Capability.clear_screen);
            terminal.flush();
        } catch (Exception ignored) {
        }
    }

    /**
     * 退出备用屏幕
     */
    private static void exitAltScreen(Terminal terminal) {
        try {
            terminal.puts(InfoCmp.Capability.exit_ca_mode);
            terminal.flush();
        } catch (Exception ignored) {
        }
    }
}