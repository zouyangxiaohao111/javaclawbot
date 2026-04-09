package gui;

import java.awt.Font;

/**
 * 统一字体管理类
 * 解决 GUI 中字体大小不一致的问题
 */
public final class UiFonts {
    private static final String FONT_FAMILY = "Microsoft YaHei UI";
    
    private UiFonts() {}
    // --- 新增通用字体大小方法 ---
    public static Font bold(float size) {
        return new Font(FONT_FAMILY, Font.BOLD, Math.round(size));
    }

    public static Font normal(float size) {
        return new Font(FONT_FAMILY, Font.PLAIN, Math.round(size));
    }
    /** 窗口标题 - 18px Bold */
    public static Font title() {
        return new Font(FONT_FAMILY, Font.BOLD, 18);
    }
    
    /** 标题级别文字 - 14px Plain */
    public static Font heading() {
        return new Font(FONT_FAMILY, Font.PLAIN, 14);
    }
    
    /** 正文、按钮、弹窗 - 13px Plain */
    public static Font body() {
        return new Font(FONT_FAMILY, Font.PLAIN, 13);
    }
    
    /** 正文加粗 - 13px Bold */
    public static Font bodyBold() {
        return new Font(FONT_FAMILY, Font.BOLD, 13);
    }
    
    /** 小标题、时间、发送者 - 11px Plain */
    public static Font caption() {
        return new Font(FONT_FAMILY, Font.PLAIN, 11);
    }
    
    /** 小标题加粗 - 11px Bold */
    public static Font captionBold() {
        return new Font(FONT_FAMILY, Font.BOLD, 11);
    }
    
    /** 输入框 - 14px Plain */
    public static Font input() {
        return new Font(FONT_FAMILY, Font.PLAIN, 14);
    }
    
    /** 系统消息、进度消息 - 12px Plain */
    public static Font small() {
        return new Font(FONT_FAMILY, Font.PLAIN, 12);
    }
    
    /** HTML 渲染使用的字体族 */
    public static String htmlFontFamily() {
        return "'Microsoft YaHei UI','Microsoft YaHei','PingFang SC','Segoe UI Emoji','Dialog'";
    }
}