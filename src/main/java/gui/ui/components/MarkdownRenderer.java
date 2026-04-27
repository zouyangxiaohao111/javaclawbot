package gui.ui.components;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级 Markdown → JavaFX 节点渲染器。
 *
 * 支持：代码块、行内代码、粗体、斜体、标题、列表、引用、链接。
 * 无需额外依赖。
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    // ── 正则 ──
    private static final Pattern CODE_BLOCK_PAT = Pattern.compile("```(\\w*)\\n?([\\s\\S]*?)```");
    private static final Pattern BOLD_PAT = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PAT = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern INLINE_CODE_PAT = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK_PAT = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    /**
     * 渲染 markdown 文本为 JavaFX 节点。
     */
    public static VBox render(String markdown) {
        VBox container = new VBox(8);
        container.setStyle("-fx-background-color: transparent;");

        if (markdown == null || markdown.isBlank()) {
            return container;
        }

        // 1) 提取代码块，用占位符替换，避免代码块内内容被误解析
        List<String> codeBlocks = new ArrayList<>();
        String processed = markdown;
        Matcher cbMatcher = CODE_BLOCK_PAT.matcher(processed);
        StringBuffer sb = new StringBuffer();
        while (cbMatcher.find()) {
            String lang = cbMatcher.group(1) != null ? cbMatcher.group(1).trim() : "";
            String code = cbMatcher.group(2);
            String label = lang.isEmpty() ? "code" : lang;
            // 去除末尾换行
            if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
            codeBlocks.add(code);
            cbMatcher.appendReplacement(sb, "\u0000CODE_" + (codeBlocks.size() - 1) + "_" + label + "\u0000");
        }
        cbMatcher.appendTail(sb);
        processed = sb.toString();

        // 2) 按行解析
        String[] lines = processed.split("\n", -1);
        List<Object> blocks = new ArrayList<>(); // String or Integer (code block index)
        StringBuilder para = new StringBuilder();
        boolean inCodePlaceholder = false;

        for (String line : lines) {
            // 检查是否整行是代码块占位符
            Matcher phMatcher = Pattern.compile("\u0000CODE_(\\d+)_(\\w*)\u0000").matcher(line.trim());
            if (phMatcher.matches()) {
                // 先提交当前段落
                flushPara(para, blocks);
                int idx = Integer.parseInt(phMatcher.group(1));
                blocks.add(idx);
                continue;
            }

            // 检查行内是否包含代码块占位符（非```代码块，而是行内的占位符引用）
            if (line.contains("\u0000CODE_")) {
                flushPara(para, blocks);
                // 拆分行内占位符
                parseInlinePlaceholders(line, blocks, codeBlocks);
                continue;
            }

            if (line.trim().isEmpty()) {
                flushPara(para, blocks);
            } else {
                if (para.length() > 0) para.append("\n");
                para.append(line);
            }
        }
        flushPara(para, blocks);

        // 3) 渲染每个块
        for (Object block : blocks) {
            if (block instanceof Integer idx) {
                container.getChildren().add(renderCodeBlock(codeBlocks.get(idx)));
            } else if (block instanceof String text) {
                container.getChildren().add(renderTextBlock(text));
            }
        }

        // 去首尾空白
        if (!container.getChildren().isEmpty()
                && container.getChildren().get(0) instanceof TextFlow tf
                && tf.getChildren().isEmpty()) {
            container.getChildren().remove(0);
        }

        return container;
    }

    private static void flushPara(StringBuilder para, List<Object> blocks) {
        if (para.length() > 0) {
            blocks.add(para.toString().trim());
            para.setLength(0);
        }
    }

    private static void parseInlinePlaceholders(String line, List<Object> blocks, List<String> codeBlocks) {
        // 行内可能混合文本和代码块占位符
        Matcher m = Pattern.compile("\u0000CODE_(\\d+)_(\\w*)\u0000").matcher(line);
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() > lastEnd) {
                String text = line.substring(lastEnd, m.start()).trim();
                if (!text.isEmpty()) blocks.add(text);
            }
            blocks.add(Integer.parseInt(m.group(1)));
            lastEnd = m.end();
        }
        if (lastEnd < line.length()) {
            String text = line.substring(lastEnd).trim();
            if (!text.isEmpty()) blocks.add(text);
        }
    }

    // ── 代码块渲染 ──
    private static Node renderCodeBlock(String code) {
        VBox box = new VBox(4);
        box.setStyle("-fx-background-color: rgba(0, 0, 0, 0.03);"
                + " -fx-background-radius: 8px;"
                + " -fx-border-color: rgba(0, 0, 0, 0.06);"
                + " -fx-border-radius: 8px;"
                + " -fx-border-width: 1px;");
        box.setPadding(new javafx.geometry.Insets(12, 16, 12, 16));

        Label codeLabel = new Label(code);
        codeLabel.setStyle("-fx-font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;"
                + " -fx-font-size: 13px;"
                + " -fx-text-fill: rgba(0, 0, 0, 0.8);");
        codeLabel.setWrapText(true);

        box.getChildren().add(codeLabel);
        return box;
    }

    // ── 文本块渲染 ──
    private static Node renderTextBlock(String text) {
        // 标题
        if (text.startsWith("### ")) {
            return createHeader(text.substring(4), 15);
        } else if (text.startsWith("## ")) {
            return createHeader(text.substring(3), 17);
        } else if (text.startsWith("# ")) {
            return createHeader(text.substring(2), 20);
        }

        // 引用
        if (text.startsWith("> ")) {
            return createBlockquote(text.substring(2));
        }

        // 无序列表
        if (text.startsWith("- ") || text.startsWith("* ")) {
            return createListItem(text.substring(2));
        }

        // 有序列表
        if (text.matches("^\\d+\\.\\s.*")) {
            return createListItem(text.replaceFirst("^\\d+\\.\\s", ""));
        }

        // 普通段落
        return createParagraph(text);
    }

    private static TextFlow createHeader(String text, double fontSize) {
        TextFlow tf = new TextFlow();
        tf.setStyle("-fx-padding: 8px 0 2px 0;");
        Text t = new Text(text);
        t.setStyle("-fx-font-weight: bold; -fx-font-size: " + fontSize + "px;"
                + " -fx-fill: rgba(0, 0, 0, 0.8);");
        tf.getChildren().add(t);
        return tf;
    }

    private static TextFlow createBlockquote(String text) {
        TextFlow tf = new TextFlow();
        tf.setStyle("-fx-background-color: rgba(0, 0, 0, 0.02);"
                + " -fx-border-color: rgba(0, 0, 0, 0.15);"
                + " -fx-border-width: 0 0 0 3px;"
                + " -fx-background-radius: 0 4px 4px 0;"
                + " -fx-border-radius: 0 4px 4px 0;");
        tf.setPadding(new javafx.geometry.Insets(6, 12, 6, 12));
        renderInlines(tf, text);
        return tf;
    }

    private static TextFlow createListItem(String text) {
        TextFlow tf = new TextFlow();
        tf.setStyle("-fx-padding: 2px 0 2px 12px;");
        Text bullet = new Text("\u2022 ");
        bullet.setStyle("-fx-fill: rgba(0, 0, 0, 0.5);");
        tf.getChildren().add(bullet);
        renderInlines(tf, text);
        return tf;
    }

    private static TextFlow createParagraph(String text) {
        TextFlow tf = new TextFlow();
        tf.setLineSpacing(2);
        renderInlines(tf, text);
        return tf;
    }

    // ── 行内解析 ──
    private static void renderInlines(TextFlow parent, String text) {
        // 同时解析：链接、粗体、斜体、行内代码
        // 使用正则逐个匹配，按位置排序插入

        List<Token> tokens = new ArrayList<>();

        // 行内代码
        Matcher cm = INLINE_CODE_PAT.matcher(text);
        while (cm.find()) {
            tokens.add(new Token(cm.start(), cm.end(), cm.group(1), TokenType.CODE));
        }

        // 粗体
        Matcher bm = BOLD_PAT.matcher(text);
        while (bm.find()) {
            if (!overlaps(tokens, bm.start(), bm.end())) {
                tokens.add(new Token(bm.start(), bm.end(), bm.group(1), TokenType.BOLD));
            }
        }

        // 斜体（需避免与粗体 ** 冲突）
        Matcher im = ITALIC_PAT.matcher(text);
        while (im.find()) {
            if (!overlaps(tokens, im.start(), im.end())) {
                tokens.add(new Token(im.start(), im.end(), im.group(1), TokenType.ITALIC));
            }
        }

        // 链接
        Matcher lm = LINK_PAT.matcher(text);
        while (lm.find()) {
            if (!overlaps(tokens, lm.start(), lm.end())) {
                tokens.add(new LinkToken(lm.start(), lm.end(), lm.group(1), lm.group(2)));
            }
        }

        // 按位置排序
        tokens.sort((a, b) -> Integer.compare(a.start, b.start));

        // 构建 Text 节点
        int pos = 0;
        for (Token token : tokens) {
            if (token.start > pos) {
                parent.getChildren().add(plainText(text.substring(pos, token.start)));
            }

            if (token instanceof LinkToken lt) {
                Text linkText = new Text(lt.text);
                linkText.setStyle("-fx-fill: #3b82f6; -fx-underline: true; -fx-cursor: hand;");
                parent.getChildren().add(linkText);
            } else {
                Text t = new Text(token.content);
                switch (token.type) {
                    case BOLD -> t.setStyle("-fx-font-weight: bold;");
                    case ITALIC -> t.setStyle("-fx-font-style: italic;");
                    case CODE -> t.setStyle("-fx-font-family: monospace;"
                            + " -fx-background-color: rgba(0, 0, 0, 0.04);"
                            + " -fx-background-radius: 3px;"
                            + " -fx-font-size: 13px;");
                }
                parent.getChildren().add(t);
            }
            pos = token.end;
        }

        if (pos < text.length()) {
            parent.getChildren().add(plainText(text.substring(pos)));
        }

        // 确保至少有一个节点
        if (parent.getChildren().isEmpty()) {
            parent.getChildren().add(plainText(""));
        }
    }

    private static Text plainText(String s) {
        Text t = new Text(s);
        t.setStyle("-fx-fill: rgba(0, 0, 0, 0.8);");
        return t;
    }

    private static boolean overlaps(List<Token> tokens, int start, int end) {
        for (Token t : tokens) {
            if (!(t instanceof LinkToken) && start < t.end && end > t.start) {
                return true;
            }
        }
        return false;
    }

    // ── 内部类型 ──

    private enum TokenType { BOLD, ITALIC, CODE }

    private static class Token {
        final int start, end;
        final String content;
        final TokenType type;
        Token(int start, int end, String content, TokenType type) {
            this.start = start; this.end = end; this.content = content; this.type = type;
        }
    }

    private static class LinkToken extends Token {
        final String text;
        @SuppressWarnings("unused") final String url;
        LinkToken(int start, int end, String text, String url) {
            super(start, end, text, TokenType.CODE); // type 不使用
            this.text = text; this.url = url;
        }
    }
}
