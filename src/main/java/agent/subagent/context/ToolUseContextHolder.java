//package agent.subagent.context;
//
//import agent.tool.ToolUseContext;
//
///**
// * 工具使用上下文持有者
// *
// * 对应 Open-ClaudeCode: AsyncLocalStorage 上下文隔离机制
// *
// * 使用 ThreadLocal 实现线程级别的上下文隔离，
// * 确保每个子代理线程只能访问自己的工具使用上下文。
// */
//public class ToolUseContextHolder {
//
//    private static final ThreadLocal<ToolUseContext> CONTEXT_HOLDER = new ThreadLocal<>();
//
//    /**
//     * 获取当前线程的工具使用上下文
//     *
//     * @return 当前上下文，如果未设置则返回 null
//     */
//    public static ToolUseContext getCurrent() {
//        return CONTEXT_HOLDER.get();
//    }
//
//    /**
//     * 设置当前线程的工具使用上下文
//     *
//     * @param context 要设置的上下文
//     */
//    public static void setCurrent(ToolUseContext context) {
//        CONTEXT_HOLDER.set(context);
//    }
//
//    /**
//     * 清除当前线程的工具使用上下文
//     */
//    public static void clear() {
//        CONTEXT_HOLDER.remove();
//    }
//
//    /**
//     * 在指定上下文中执行操作
//     *
//     * @param context 要使用的上下文
//     * @param action 要执行的操作
//     * @param <T> 返回值类型
//     * @return 操作结果
//     */
//    public static <T> T runWithContext(ToolUseContext context, ContextualAction<T> action) {
//        ToolUseContext previous = CONTEXT_HOLDER.get();
//        try {
//            CONTEXT_HOLDER.set(context);
//            return action.run();
//        } finally {
//            if (previous != null) {
//                CONTEXT_HOLDER.set(previous);
//            } else {
//                CONTEXT_HOLDER.remove();
//            }
//        }
//    }
//
//    /**
//     * 在指定上下文中执行无返回值操作
//     *
//     * @param context 要使用的上下文
//     * @param action 要执行的操作
//     */
//    public static void runWithContext(ToolUseContext context, Runnable action) {
//        ToolUseContext previous = CONTEXT_HOLDER.get();
//        try {
//            CONTEXT_HOLDER.set(context);
//            action.run();
//        } finally {
//            if (previous != null) {
//                CONTEXT_HOLDER.set(previous);
//            } else {
//                CONTEXT_HOLDER.remove();
//            }
//        }
//    }
//
//    /**
//     * 上下文操作接口
//     */
//    @FunctionalInterface
//    public interface ContextualAction<T> {
//        T run();
//    }
//}
