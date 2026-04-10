package gui.javafx.service;

import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownRenderer {
    private static final Pattern CODE_PATTERN = Pattern.compile("```[\\s\\S]*?```|`[^`]+`");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*[^*]+\\*\\*|__[^_]+__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*[^*]+\\*|[^\\*]+[^\\*]");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[[^\\]]+\\]\\([^)]+\\)");

    public StyleSpans<?> computeStyles(String text) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();

        int lastEnd = 0;
        Matcher matcher = CODE_PATTERN.matcher(text);

        while (matcher.find()) {
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singletonList("code"), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }

        builder.add(Collections.emptyList(), text.length() - lastEnd);

        return builder.create();
    }
}