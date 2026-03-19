package memory;

import memory.MemoryHybridSearch;
import memory.MemoryIndexManager;
import memory.MemoryMMR;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.List;
import java.util.Set;

/**
 * Memory FTS / Java fallback 调试类
 *
 * 作用：
 * 1. 检查 SQLite 数据库是否有 chunks / chunks_fts 数据
 * 2. 打印 buildFtsQuery(query) 的实际结果
 * 3. 直接执行 FTS SQL，确认是不是 SQL / 查询串问题
 * 4. 用 Java fallback 逻辑验证是否能命中
 *
 * 使用方式：
 *   java debug.MemoryFtsDebugTest "你的查询词"
 *
 * 也可以直接在 IDE 里运行 main 方法
 */
public class MemoryFtsDebugTest {

    public static void main(String[] args) throws Exception {
        // ===== 1) 改成你的工作目录 =====
        Path workspaceDir = Paths.get("C:\\Users\\19247\\.javaclawbot\\workspace");

        // ===== 2) 查询词：优先读命令行参数，否则给一个默认值 =====
        String query = args != null && args.length > 0
                ? String.join(" ", args)
                : "memory";

        System.out.println("==================================================");
        System.out.println("Memory FTS 调试开始");
        System.out.println("workspaceDir = " + workspaceDir.toAbsolutePath());
        System.out.println("query        = " + query);
        System.out.println("==================================================");

        MemoryIndexManager manager = new MemoryIndexManager(workspaceDir);
        manager.initialize();

        try {
            // 先确保做一次同步
            manager.sync();

            // 取出内部 dbConnection（因为你当前类里没有公开 getter）
            Connection conn = getConnection(manager);

            // 1) 基础计数
            printTableCounts(conn);

            // 2) 打印 chunks 表样本
            //printChunksSample(conn);

            // 3) 打印 chunks_fts 表样本
            //printFtsSample(conn);

            // 4) 打印 buildFtsQuery 的结果
            String ftsQuery = MemoryHybridSearch.buildFtsQuery(query);
            System.out.println("\n[1] buildFtsQuery(query) 结果：");
            System.out.println("query    = " + query);
            System.out.println("ftsQuery = " + ftsQuery);

            // 5) 直接执行最简单 FTS SQL（原始 query）
            /*System.out.println("\n[2] 直接 FTS 查询（使用原始 query）");
            runDirectFts(conn, query, 10);*/

            // 6) 直接执行 buildFtsQuery 后的 FTS SQL
            /*if (ftsQuery != null && !ftsQuery.isBlank()) {
                System.out.println("\n[3] 直接 FTS 查询（使用 buildFtsQuery 结果）");
                runDirectFts(conn, ftsQuery, 10);
            } else {
                System.out.println("\n[3] buildFtsQuery 为空，跳过");
            }*/

            List<MemoryHybridSearch.HybridResult> search = manager.search(query, 10, 0.35, null);

            /*// 7) 用 Java fallback 逻辑测试
            System.out.println("\n[4] Java fallback 搜索测试");
            runJavaFallback(conn, query, 10);

            // 8) 调用 manager.search() 实际看看
            System.out.println("\n[5] 通过 MemoryIndexManager.search() 测试");
            List<MemoryHybridSearch.HybridResult> results = manager.search(query, 10, 0.0, null);
            if (results.isEmpty()) {
                System.out.println("manager.search() 返回 0 条结果");
            } else {
                for (int i = 0; i < results.size(); i++) {
                    MemoryHybridSearch.HybridResult r = results.get(i);
                    System.out.println("结果 " + (i + 1) + ":");
                    System.out.println("  path       = " + r.path);
                    System.out.println("  lines      = " + r.startLine + "-" + r.endLine);
                    System.out.println("  score      = " + r.score);
                    System.out.println("  vector     = " + r.vectorScore);
                    System.out.println("  text       = " + r.textScore);
                    System.out.println("  source     = " + r.source);
                    System.out.println("  snippet    = " + trim(r.snippet, 160));
                    System.out.println();
                }
            }

            System.out.println("==================================================");
            System.out.println("调试结束");
            System.out.println("==================================================");
            */
        } finally {
            manager.close();
        }
    }

    /**
     * 通过反射拿到 MemoryIndexManager 内部的 dbConnection
     */
    private static Connection getConnection(MemoryIndexManager manager) throws Exception {
        Field f = MemoryIndexManager.class.getDeclaredField("dbConnection");
        f.setAccessible(true);
        return (Connection) f.get(manager);
    }

    /**
     * 打印基础表数量
     */
    private static void printTableCounts(Connection conn) throws SQLException {
        System.out.println("\n[基础计数]");

        printCount(conn, "SELECT COUNT(*) FROM files", "files");
        printCount(conn, "SELECT COUNT(*) FROM chunks", "chunks");

        // chunks_fts 可能不存在，所以单独 try
        try {
            printCount(conn, "SELECT COUNT(*) FROM chunks_fts", "chunks_fts");
        } catch (SQLException e) {
            System.out.println("chunks_fts 计数失败: " + e.getMessage());
        }
    }

    private static void printCount(Connection conn, String sql, String label) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                System.out.println(label + " = " + rs.getInt(1));
            }
        }
    }

    /**
     * 打印 chunks 表样本
     */
    private static void printChunksSample(Connection conn) throws SQLException {
        System.out.println("\n[chunks 样本]");

        String sql = """
                SELECT id, path, source, start_line, end_line, substr(text, 1, 120) AS snippet
                FROM chunks
                ORDER BY rowid DESC
                LIMIT 5
                """;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int i = 0;
            while (rs.next()) {
                i++;
                System.out.println("chunk " + i + ":");
                System.out.println("  id      = " + rs.getString("id"));
                System.out.println("  path    = " + rs.getString("path"));
                System.out.println("  source  = " + rs.getString("source"));
                System.out.println("  lines   = " + rs.getInt("start_line") + "-" + rs.getInt("end_line"));
                System.out.println("  snippet = " + rs.getString("snippet"));
            }
            if (i == 0) {
                System.out.println("chunks 表无数据");
            }
        }
    }

    /**
     * 打印 FTS 表样本
     */
    private static void printFtsSample(Connection conn) {
        System.out.println("\n[chunks_fts 样本]");

        String sql = """
                SELECT id, path, source, start_line, end_line, substr(text, 1, 120) AS snippet
                FROM chunks_fts
                LIMIT 5
                """;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int i = 0;
            while (rs.next()) {
                i++;
                System.out.println("fts " + i + ":");
                System.out.println("  id      = " + rs.getString("id"));
                System.out.println("  path    = " + rs.getString("path"));
                System.out.println("  source  = " + rs.getString("source"));
                System.out.println("  lines   = " + rs.getInt("start_line") + "-" + rs.getInt("end_line"));
                System.out.println("  snippet = " + rs.getString("snippet"));
            }
            if (i == 0) {
                System.out.println("chunks_fts 表无数据");
            }
        } catch (SQLException e) {
            System.out.println("读取 chunks_fts 失败: " + e.getMessage());
        }
    }

    /**
     * 直接执行 FTS SQL
     */
    private static void runDirectFts(Connection conn, String matchExpr, int limit) {
        String sql = """
                SELECT id, path, source, start_line, end_line, bm25(chunks_fts) AS rank,
                       substr(text, 1, 160) AS snippet
                FROM chunks_fts
                WHERE chunks_fts MATCH ?
                ORDER BY rank ASC
                LIMIT ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchExpr);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                int i = 0;
                while (rs.next()) {
                    i++;
                    System.out.println("FTS结果 " + i + ":");
                    System.out.println("  path    = " + rs.getString("path"));
                    System.out.println("  source  = " + rs.getString("source"));
                    System.out.println("  lines   = " + rs.getInt("start_line") + "-" + rs.getInt("end_line"));
                    System.out.println("  rank    = " + rs.getDouble("rank"));
                    System.out.println("  snippet = " + rs.getString("snippet"));
                }
                if (i == 0) {
                    System.out.println("FTS 查询结果为空");
                }
            }
        } catch (SQLException e) {
            System.out.println("FTS 查询异常: " + e.getMessage());
        }
    }

    /**
     * 纯 Java fallback 搜索测试
     *
     * 逻辑对齐你现在 searchKeywordJava 的思路：
     * - tokenize(query)
     * - tokenize(chunk.text)
     * - Jaccard similarity
     */
    private static void runJavaFallback(Connection conn, String query, int limit) throws Exception {
        Set<String> queryTokens = MemoryMMR.tokenize(query);
        System.out.println("queryTokens = " + queryTokens);

        String sql = """
                SELECT id, path, source, start_line, end_line, text
                FROM chunks
                """;

        class Row {
            String id;
            String path;
            String source;
            int startLine;
            int endLine;
            String text;
            double score;
        }

        java.util.ArrayList<Row> rows = new java.util.ArrayList<>();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String text = rs.getString("text");
                Set<String> chunkTokens = MemoryMMR.tokenize(text);
                double sim = MemoryMMR.jaccardSimilarity(queryTokens, chunkTokens);
                if (sim > 0) {
                    Row r = new Row();
                    r.id = rs.getString("id");
                    r.path = rs.getString("path");
                    r.source = rs.getString("source");
                    r.startLine = rs.getInt("start_line");
                    r.endLine = rs.getInt("end_line");
                    r.text = text;
                    r.score = sim;
                    rows.add(r);
                }
            }
        }

        rows.sort((a, b) -> Double.compare(b.score, a.score));

        if (rows.isEmpty()) {
            System.out.println("Java fallback 结果为空");
            return;
        }

        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Row r = rows.get(i);
            System.out.println("Java结果 " + (i + 1) + ":");
            System.out.println("  path    = " + r.path);
            System.out.println("  source  = " + r.source);
            System.out.println("  lines   = " + r.startLine + "-" + r.endLine);
            System.out.println("  score   = " + r.score);
            System.out.println("  snippet = " + trim(r.text, 160));
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}