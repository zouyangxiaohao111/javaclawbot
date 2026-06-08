package agent.tool.db;

import agent.tool.Tool;
import agent.tool.ToolUseContext;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.GsonFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Database query tool. Supports multi-datasource, parameterized queries,
 * transaction management, and automatic pagination.
 */
public class DbTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(DbTool.class);

    private final DataSourceManager dsManager;

    /** Active transactions: datasource name → Connection */
    private final Map<String, Connection> transactions = new HashMap<>();

    public DbTool(DataSourceManager dsManager) {
        this.dsManager = dsManager;
        log.info("初始化 DbTool");
    }

    @Override
    public String name() {
        return "db";
    }

    @Override
    public String description() {
        return String.join("\n",
                "Executes SQL queries against a database.",
                "Supports multi-datasource, parameterized queries, transactions, and pagination.",
                "",
                "Parameters:",
                "- sql (required): SQL statement(s). Multiple statements separated by ';'.",
                "- params (optional): Named parameter bindings, e.g. {\":name\": \"value\"}.",
                "- datasource (optional): Datasource name, defaults to 'default'.",
                "- transaction (optional): 'begin' | 'commit' | 'rollback' for explicit transaction control.",
                "- page (optional): Page number for pagination (default 1).",
                "- page_size (optional): Rows per page (default 500).",
                "",
                "Notes:",
                "- Read-only SQL (SELECT/SHOW/DESCRIBE/EXPLAIN) executes directly.",
                "- Destructive SQL (INSERT/UPDATE/DELETE/DDL) requires user confirmation via AskUserQuestion.",
                "- Use BEGIN/COMMIT/ROLLBACK for transaction control.",
                "- Large result sets are paginated; use page parameter to navigate.",
                "- **所有数据库操作必须使用 db 工具，禁止使用 Bash 或其他工具执行数据库操作。**"
                );
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> sqlProp = new LinkedHashMap<>();
        sqlProp.put("type", "string");
        sqlProp.put("description", "SQL statement(s) to execute. Multiple statements separated by ';'.");
        properties.put("sql", sqlProp);

        Map<String, Object> paramsProp = new LinkedHashMap<>();
        paramsProp.put("type", "object");
        paramsProp.put("description", "Named parameter bindings, e.g. {\":name\": \"value\"}.");
        properties.put("params", paramsProp);

        Map<String, Object> dsProp = new LinkedHashMap<>();
        dsProp.put("type", "string");
        dsProp.put("description", "Datasource name. Defaults to 'default'.");
        dsProp.put("default", "default");
        properties.put("datasource", dsProp);

        Map<String, Object> txProp = new LinkedHashMap<>();
        txProp.put("type", "string");
        txProp.put("description", "Transaction control: 'begin', 'commit', or 'rollback'.");
        txProp.put("enum", Arrays.asList("begin", "commit", "rollback"));
        properties.put("transaction", txProp);

        Map<String, Object> pageProp = new LinkedHashMap<>();
        pageProp.put("type", "integer");
        pageProp.put("description", "Page number (1-based). Default 1.");
        pageProp.put("default", 1);
        pageProp.put("minimum", 1);
        properties.put("page", pageProp);

        Map<String, Object> psProp = new LinkedHashMap<>();
        psProp.put("type", "integer");
        psProp.put("description", "Rows per page. Default 500.");
        psProp.put("default", 500);
        psProp.put("minimum", 1);
        psProp.put("maximum", 10000);
        properties.put("page_size", psProp);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("sql"));

        return schema;
    }

    // ==================== 覆写 execute(Map, ToolUseContext) ====================
    // 当 AgentLoop 提供了 ToolUseContext 时使用此方法。
    // 检测到破坏性 SQL 且无用户确认时，返回 AskUserQuestion 格式响应，
    // AgentLoop 检测到 awaiting_response 后暂停等待用户确认。

    @Override
    public CompletionStage<String> execute(Map<String, Object> args, ToolUseContext parentUseContext) {
        // 前置检查：是否需要用户确认
        String sql = (String) args.get("sql");
        if (sql != null) {
            // Strip leading comments before checking for destructive SQL
            String stripped = stripLeadingComments(sql);
            if (stripped.isEmpty()) return execute(args);
            String upper = stripped.toUpperCase();
            String firstStmt = upper.split(";")[0].trim();
            if (isDestructive(firstStmt)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = args.containsKey("params")
                        ? (Map<String, Object>) args.get("params")
                        : Collections.emptyMap();
                String confirmKey = ":confirm";
                String paramStr = String.valueOf(params.getOrDefault(confirmKey, "false"));
                if (!"true".equalsIgnoreCase(paramStr)) {
                    // 返回 AskUserQuestion 格式，强制触发 AgentLoop 暂停
                    List<Map<String, Object>> questions = List.of(Map.of(
                            "question", "是否允许执行以下 SQL 操作？\n"+firstStmt,
                            "header", "数据库操作",
                            "options", List.of(
                                    Map.of("label", "允许", "description", "允许执行该 SQL 语句"),
                                    Map.of("label", "拒绝", "description", "拒绝执行该 SQL 语句")
                            )
                    ));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("questions", questions);
                    result.put("answers", Map.of());
                    result.put("status", "awaiting_response");
                    result.put("_sql", sql.trim());
                    return CompletableFuture.completedFuture(GsonFactory.toJson(result));
                }
            }
        }
        return execute(args);
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doExecute(args);
            } catch (Exception e) {
                log.error("DbTool execution error", e);
                return GsonFactory.toJson(Map.of("error", e.getMessage()));
            }
        });
    }

    private String doExecute(Map<String, Object> args) throws Exception {
        String sql = (String) args.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return GsonFactory.toJson(Map.of("error", "sql parameter is required"));
        }
        sql = sql.trim();

        String dsName = args.containsKey("datasource") ? (String) args.get("datasource") : "default";

        @SuppressWarnings("unchecked")
        Map<String, Object> params = args.containsKey("params")
                ? (Map<String, Object>) args.get("params")
                : Collections.emptyMap();

        String txOp = (String) args.get("transaction");

        // Transaction control
        if (txOp != null) {
            return handleTransaction(dsName, txOp, sql, params);
        }

        // If in a transaction for this datasource, use the tx connection
        Connection conn;
        boolean isTx = false;
        synchronized (transactions) {
            isTx = transactions.containsKey(dsName);
            conn = isTx ? transactions.get(dsName) : null;
        }

        if (conn == null) {
            HikariDataSource ds = dsManager.getDataSource(dsName);
            if (ds == null) {
                String available = String.join(", ", dsManager.listDataSources().keySet());
                return GsonFactory.toJson(Map.of("error", "Datasource '" + dsName + "' not found. Available: " + available));
            }
            conn = ds.getConnection();
        }

        try {
            // Split multiple statements
            String[] statements = splitStatements(sql);
            if (statements.length > 1) {
                return executeBatch(conn, statements, params, isTx);
            }

            String trimmed = statements[0].trim().toUpperCase();

            // Execute single statement
            return executeStatement(conn, statements[0], params, isTx, args, dsName);
        } catch (Exception e) {
            if (!isTx) {
                try {
                    if (!conn.getAutoCommit()) {
                        conn.rollback();
                    }
                } catch (SQLException rollbackEx) {
                    log.warn("Rollback failed on autoCommit connection", rollbackEx);
                }
            }
            throw e;
        } finally {
            if (!isTx && conn != null) {
                conn.close();
            }
        }
    }

    /**
     * Strip leading SQL comments (-- line comments and /* block comments * /) to find the first real keyword.
     */
    private String stripLeadingComments(String sql) {
        String s = sql.trim();
        while (true) {
            if (s.startsWith("--")) {
                int eol = s.indexOf('\n');
                if (eol < 0) return "";
                s = s.substring(eol + 1).trim();
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) return "";
                s = s.substring(end + 2).trim();
            } else {
                break;
            }
        }
        return s;
    }

    /**
     * Strip all SQL comments (both -- line comments and /* block comments * /)
     * from the entire SQL string. Used before wrapping queries or appending clauses
     * so that inline/end-of-line comments don't break the transformed SQL.
     */
    private String stripAllComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            if (i + 1 < sql.length() && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                // Single-line comment: skip to end of line
                int eol = sql.indexOf('\n', i);
                if (eol < 0) break; // rest of string is comment
                i = eol + 1;
                sb.append('\n'); // preserve structure for line-sensitive SQL
            } else if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                // Block comment: skip to * /
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) break; // unclosed comment, treat rest as comment
                i = end + 2;
            } else {
                sb.append(sql.charAt(i));
                i++;
            }
        }
        return sb.toString().trim();
    }

    private boolean isDestructive(String sql) {
        return sql.startsWith("INSERT")
                || sql.startsWith("UPDATE")
                || sql.startsWith("DELETE")
                || sql.startsWith("TRUNCATE")
                || sql.startsWith("DROP")
                || sql.startsWith("ALTER")
                || sql.startsWith("CREATE")
                || sql.startsWith("REPLACE");
    }

    private String executeStatement(Connection conn, String sql, Map<String, Object> params,
                                     boolean isTx, Map<String, Object> args, String dsName) throws Exception {
        // Strip leading comments before routing to the correct execution method
        String cleaned = stripLeadingComments(sql);
        if (cleaned.isEmpty()) {
            return GsonFactory.toJson(Map.of("error", "Empty SQL statement after stripping comments"));
        }
        String trimmed = cleaned.toUpperCase();

        if (trimmed.startsWith("SELECT") || trimmed.startsWith("SHOW")
                || trimmed.startsWith("DESCRIBE") || trimmed.startsWith("EXPLAIN")
                || trimmed.startsWith("WITH")) {
            return executeQuery(conn, sql, params, args, dsName);
        } else if (trimmed.startsWith("INSERT") || trimmed.startsWith("UPDATE")
                || trimmed.startsWith("DELETE") || trimmed.startsWith("REPLACE")) {
            return executeUpdate(conn, sql, params, isTx);
        } else {
            // DDL or other
            return executeUpdate(conn, sql, params, isTx);
        }
    }

    private String executeQuery(Connection conn, String sql, Map<String, Object> params,
                                 Map<String, Object> args, String dsName) throws Exception {
        sql = sql.trim();
        // Strip all comments before pagination/transformation to prevent inline
        // or end-of-line comments from breaking the appended LIMIT/OFFSET and COUNT subquery
        String strippedSql = stripAllComments(sql);
        if (strippedSql.isEmpty()) {
            return GsonFactory.toJson(Map.of("error", "Empty SQL after stripping comments"));
        }

        // Handle pagination
        int page = args.containsKey("page") ? ((Number) args.get("page")).intValue() : 1;
        int pageSize = args.containsKey("page_size") ? ((Number) args.get("page_size")).intValue() : 500;

        String countSql = "SELECT COUNT(*) AS _total FROM (" + strippedSql + ") _sub";
        int totalRows;
        try (PreparedStatement countStmt = prepareStatement(conn, countSql, params);
             ResultSet rs = countStmt.executeQuery()) {
            rs.next();
            totalRows = rs.getInt(1);
        }

        int offset = (page - 1) * pageSize;
        String pagedSql = strippedSql + " LIMIT ? OFFSET ?";

        try (PreparedStatement stmt = conn.prepareStatement(pagedSql)) {
            NamedParameterProcessor processor = new NamedParameterProcessor(strippedSql);
            List<Object> values = processor.toPositionalValues(params);
            for (int i = 0; i < values.size(); i++) {
                stmt.setObject(i + 1, values.get(i));
            }
            // Bind LIMIT and OFFSET
            stmt.setInt(values.size() + 1, pageSize);
            stmt.setInt(values.size() + 2, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        // Convert non-JSON-safe types to string
                        if (val != null && !(val instanceof Number)
                                && !(val instanceof Boolean)
                                && !(val instanceof String)) {
                            val = val.toString();
                        }
                        row.put(meta.getColumnLabel(i), val);
                    }
                    rows.add(row);
                }

                boolean hasMore = (offset + pageSize) < totalRows;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", rows);
                result.put("_page", page);
                result.put("_pageSize", pageSize);
                result.put("_totalRows", totalRows);
                result.put("_hasMore", hasMore);

                return GsonFactory.toJson(result);
            }
        }
    }

    private String executeUpdate(Connection conn, String sql, Map<String, Object> params,
                                  boolean isTx) throws Exception {
        try (PreparedStatement stmt = prepareStatement(conn, sql, params)) {
            int affected = stmt.executeUpdate();
            if (!isTx && !conn.getAutoCommit()) {
                conn.commit();
            }
            return GsonFactory.toJson(Map.of("affected_rows", affected));
        }
    }

    private String executeBatch(Connection conn, String[] statements, Map<String, Object> params,
                                 boolean isTx) throws Exception {
        List<String> results = new ArrayList<>();
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            results.add(executeStatement(conn, trimmed, params, isTx, Collections.emptyMap(), ""));
        }
        return "[" + String.join(",", results) + "]";
    }

    private String handleTransaction(String dsName, String txOp, String sql, Map<String, Object> params) throws Exception {
        switch (txOp.toLowerCase()) {
            case "begin": {
                HikariDataSource ds = dsManager.getDataSource(dsName);
                if (ds == null) {
                    return GsonFactory.toJson(Map.of("error", "Datasource '" + dsName + "' not found"));
                }
                synchronized (transactions) {
                    if (transactions.containsKey(dsName)) {
                        return GsonFactory.toJson(Map.of("error", "Transaction already active for datasource '" + dsName + "'"));
                    }
                    Connection conn = ds.getConnection();
                    conn.setAutoCommit(false);
                    transactions.put(dsName, conn);
                }
                Map<String, Object> beginMap = new LinkedHashMap<>();
                beginMap.put("transaction", "begin");
                beginMap.put("datasource", dsName);
                return GsonFactory.toJson(beginMap);
            }
            case "commit": {
                synchronized (transactions) {
                    Connection conn = transactions.remove(dsName);
                    if (conn == null) {
                        return GsonFactory.toJson(Map.of("error", "No active transaction for datasource '" + dsName + "'"));
                    }
                    conn.commit();
                    conn.setAutoCommit(true);
                    conn.close();
                }
                return GsonFactory.toJson(Map.of("transaction", "commit", "datasource", dsName));
            }
            case "rollback": {
                synchronized (transactions) {
                    Connection conn = transactions.remove(dsName);
                    if (conn == null) {
                        return GsonFactory.toJson(Map.of("error", "No active transaction for datasource '" + dsName + "'"));
                    }
                    conn.rollback();
                    conn.setAutoCommit(true);
                    conn.close();
                }
                return GsonFactory.toJson(Map.of("transaction", "rollback", "datasource", dsName));
            }
            default:
                return GsonFactory.toJson(Map.of("error", "Invalid transaction operation: " + txOp));
        }
    }

    private PreparedStatement prepareStatement(Connection conn, String sql, Map<String, Object> params) throws Exception {
        NamedParameterProcessor processor = new NamedParameterProcessor(sql);
        String parsedSql = processor.getParsedSql();
        PreparedStatement stmt = conn.prepareStatement(parsedSql);
        List<Object> values = processor.toPositionalValues(params);
        for (int i = 0; i < values.size(); i++) {
            stmt.setObject(i + 1, values.get(i));
        }
        return stmt;
    }

    private String[] splitStatements(String sql) {
        return sql.split(";");
    }
}
