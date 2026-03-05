package agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dankito.readability4j.extended.Readability4JExtended;
import org.jsoup.Jsoup;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebFetchTool extends Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Shared constants (match Python)
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_2) AppleWebKit/537.36";
    private static final int MAX_REDIRECTS = 5;
    private static final int DEFAULT_MAX_CHARS = 50_000;

    private final HttpClient http;
    private final int defaultMaxChars;

    public WebFetchTool(Integer maxChars) {
        this.defaultMaxChars = (maxChars == null || maxChars < 100) ? DEFAULT_MAX_CHARS : maxChars;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER) // manual redirect to enforce MAX_REDIRECTS
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch URL and extract readable content (HTML → markdown/text).";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", Map.of("type", "string", "description", "URL to fetch"));
        props.put("extractMode", Map.of(
                "type", "string",
                "enum", List.of("markdown", "text"),
                "default", "markdown"
        ));
        props.put("maxChars", Map.of(
                "type", "integer",
                "minimum", 100
        ));

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("url")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        String url = String.valueOf(args.getOrDefault("url", "")).trim();
        String extractMode = String.valueOf(args.getOrDefault("extractMode", "markdown")).trim();
        int maxChars = defaultMaxChars;

        Object mc = args.get("maxChars");
        if (mc instanceof Number n) maxChars = n.intValue();

        // validate url first
        UrlCheck chk = validateUrl(url);
        if (!chk.ok) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("error", "URL validation failed: " + chk.error);
            out.put("url", url);
            return CompletableFuture.completedFuture(out.toString());
        }

        final int finalMaxChars = maxChars;
        final String finalExtractMode = extractMode;

        return fetchFollowRedirects(url, 0)
                .thenApply(resp -> {
                    try {
                        String finalUrl = resp.finalUrl;
                        int status = resp.statusCode;
                        String ctype = resp.contentType == null ? "" : resp.contentType.toLowerCase(Locale.ROOT);
                        String body = resp.body == null ? "" : resp.body;

                        String extractor;
                        String text;

                        if (ctype.contains("application/json")) {
                            extractor = "json";
                            try {
                                JsonNode node = MAPPER.readTree(body);
                                text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
                            } catch (Exception e) {
                                // fallback raw if invalid json
                                text = body;
                            }
                        } else if (ctype.contains("text/html") || looksLikeHtml(body)) {
                            // Readability
                            Extracted ex = extractReadable(finalUrl, body, finalExtractMode);
                            extractor = ex.extractor;
                            text = ex.text;
                        } else {
                            extractor = "raw";
                            text = body;
                        }

                        boolean truncated = text != null && text.length() > finalMaxChars;
                        if (truncated) text = text.substring(0, finalMaxChars);

                        ObjectNode out = MAPPER.createObjectNode();
                        out.put("url", url);
                        out.put("finalUrl", finalUrl);
                        out.put("status", status);
                        out.put("extractor", extractor);
                        out.put("truncated", truncated);
                        out.put("length", text == null ? 0 : text.length());
                        out.put("text", text == null ? "" : text);
                        return out.toString();
                    } catch (Exception e) {
                        ObjectNode out = MAPPER.createObjectNode();
                        out.put("error", String.valueOf(e.getMessage()));
                        out.put("url", url);
                        return out.toString();
                    }
                })
                .exceptionally(ex -> {
                    ObjectNode out = MAPPER.createObjectNode();
                    out.put("error", rootMessage(ex));
                    out.put("url", url);
                    return out.toString();
                });
    }

    // -----------------------
    // Fetch with redirect cap
    // -----------------------

    private CompletionStage<FetchResp> fetchFollowRedirects(String url, int depth) {
        if (depth > MAX_REDIRECTS) {
            CompletableFuture<FetchResp> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("Too many redirects (>" + MAX_REDIRECTS + ")"));
            return f;
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(resp -> {
                    int code = resp.statusCode();
                    if (code / 100 == 3) {
                        Optional<String> loc = resp.headers().firstValue("location");
                        if (loc.isPresent()) {
                            String next = resolveRedirect(url, loc.get());
                            return fetchFollowRedirects(next, depth + 1);
                        }
                    }
                    FetchResp fr = new FetchResp();
                    fr.finalUrl = url;
                    fr.statusCode = code;
                    fr.body = resp.body();
                    fr.contentType = resp.headers().firstValue("content-type").orElse("");
                    return CompletableFuture.completedFuture(fr);
                });
    }

    private static String resolveRedirect(String base, String location) {
        try {
            URI baseUri = URI.create(base);
            URI loc = URI.create(location);
            if (loc.isAbsolute()) return loc.toString();
            return baseUri.resolve(loc).toString();
        } catch (Exception e) {
            return location;
        }
    }

    // -----------------------
    // Readability extract
    // -----------------------

    private static Extracted extractReadable(String url, String rawHtml, String extractMode) {
        try {
            // Readability4JExtended(baseURL, rawHTML).parse()
            var article = new Readability4JExtended(url, rawHtml).parse();
            String title = article != null ? nullToEmpty(article.getTitle()) : "";
            String contentHtml = article != null ? nullToEmpty(article.getContent()) : "";

            String content;
            if ("text".equalsIgnoreCase(extractMode)) {
                content = stripTags(contentHtml);
            } else {
                // "markdown": do minimal HTML->MD like Python (links/headings/lists) then strip tags
                content = toMarkdown(contentHtml);
            }

            String text = content;
            if (!title.isBlank()) {
                text = "# " + title + "\n\n" + content;
            }

            Extracted out = new Extracted();
            out.extractor = "readability";
            out.text = normalize(text);
            return out;
        } catch (Exception e) {
            // If readability fails, fallback to raw stripped text
            Extracted out = new Extracted();
            out.extractor = "raw_html_fallback";
            out.text = normalize(stripTags(rawHtml));
            return out;
        }
    }

    // -----------------------
    // Helpers (match Python behavior)
    // -----------------------

    private static boolean looksLikeHtml(String s) {
        if (s == null) return false;
        String head = s.length() > 256 ? s.substring(0, 256).toLowerCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.contains("<body") || head.contains("<head");
    }

    private static UrlCheck validateUrl(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return UrlCheck.fail("Only http/https allowed, got '" + (scheme == null ? "none" : scheme) + "'");
            }
            String host = u.getHost();
            // URI.getHost can be null for some valid URLs, fallback to authority parsing
            if (host == null || host.isBlank()) {
                String auth = u.getRawAuthority();
                if (auth == null || auth.isBlank()) return UrlCheck.fail("Missing domain");
            }
            return UrlCheck.ok();
        } catch (URISyntaxException e) {
            return UrlCheck.fail(e.getMessage());
        } catch (Exception e) {
            return UrlCheck.fail(String.valueOf(e.getMessage()));
        }
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        // Jsoup is the safest here; it also decodes entities.
        return Jsoup.parse(html).text().trim();
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String t = text.replaceAll("[ \\t]+", " ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    private static String toMarkdown(String html) {
        if (html == null) return "";

        String text = html;

        // links: <a href="...">text</a> -> [text](url)
        text = replaceAll(text,
                Pattern.compile("<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE),
                m -> "[" + stripTags(m.group(2)) + "](" + m.group(1) + ")"
        );

        // headings: <h1>..</h1> -> # ..
        text = replaceAll(text,
                Pattern.compile("<h([1-6])[^>]*>([\\s\\S]*?)</h\\1>", Pattern.CASE_INSENSITIVE),
                m -> "\n" + "#".repeat(Integer.parseInt(m.group(1))) + " " + stripTags(m.group(2)) + "\n"
        );

        // list items: <li>..</li> -> - ..
        text = replaceAll(text,
                Pattern.compile("<li[^>]*>([\\s\\S]*?)</li>", Pattern.CASE_INSENSITIVE),
                m -> "\n- " + stripTags(m.group(1))
        );

        // paragraph-ish breaks
        text = text.replaceAll("</(p|div|section|article)>", "\n\n");
        text = text.replaceAll("<(br|hr)\\s*/?>", "\n");

        return normalize(stripTags(text));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }

    private static String replaceAll(String input, Pattern pattern, Replacer replacer) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = replacer.replace(m);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @FunctionalInterface
    private interface Replacer {
        String replace(Matcher m);
    }

    private static final class UrlCheck {
        final boolean ok;
        final String error;

        private UrlCheck(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        static UrlCheck ok() { return new UrlCheck(true, ""); }
        static UrlCheck fail(String err) { return new UrlCheck(false, err == null ? "" : err); }
    }

    private static final class FetchResp {
        String finalUrl;
        int statusCode;
        String contentType;
        String body;
    }

    private static final class Extracted {
        String extractor;
        String text;
    }
}