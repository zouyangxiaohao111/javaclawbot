package gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * 命令补全弹窗
 * 
 * 在输入框上方显示浮动提示，支持键盘/鼠标选择和 Tab 补全
 */
public class CommandCompletionPopup {
    
    private static final int MAX_VISIBLE_ITEMS = 8;
    private static final int ITEM_HEIGHT = 28;
    private static final int POPUP_WIDTH = 320;
    
    private final JWindow window;
    private final JTextArea inputArea;
    private final JFrame parentFrame;
    private final List<CommandItem> allCommands;
    private final List<CommandItem> filteredCommands = new ArrayList<>();
    private final JPanel contentPanel;
    private final JScrollPane scrollPane;
    
    private int selectedIndex = 0;
    private boolean visible = false;
    
    // 颜色定义
    private static final Color POPUP_BG = new Color(255, 255, 255, 220);  // 半透明白色
    private static final Color ITEM_BG_NORMAL = new Color(0, 0, 0, 0);
    private static final Color ITEM_BG_SELECTED = new Color(0, 122, 255, 40);  // 浅蓝
    private static final Color TEXT_PRIMARY = new Color(28, 28, 30);
    private static final Color TEXT_SECONDARY = new Color(99, 99, 102);
    
    /**
     * 命令项
     */
    public record CommandItem(String command, String description) {}
    
    public CommandCompletionPopup(JFrame parentFrame, JTextArea inputArea, boolean developerMode) {
        this.parentFrame = parentFrame;
        this.inputArea = inputArea;
        this.allCommands = buildCommands(developerMode);
        this.window = createWindow();
        this.contentPanel = new JPanel();
        this.contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        this.contentPanel.setBackground(POPUP_BG);
        
        this.scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(ITEM_HEIGHT);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        
        window.setContentPane(scrollPane);
        
        setupKeyListener();
    }
    
    /**
     * 构建命令列表
     */
    private List<CommandItem> buildCommands(boolean developerMode) {
        List<CommandItem> commands = new ArrayList<>();
        
        // 系统命令（来自 AgentLoop.system_cmd）
        commands.add(new CommandItem("/stop", "停止当前任务"));
        commands.add(new CommandItem("/help", "显示帮助信息"));
        commands.add(new CommandItem("/clear", "清空对话历史"));
        commands.add(new CommandItem("/memory", "搜索记忆"));
        commands.add(new CommandItem("/mcp-reload", "重新加载 MCP 工具"));
        commands.add(new CommandItem("/mcp-init", "初始化 MCP 连接"));
        commands.add(new CommandItem("/context-press", "压缩上下文"));
        commands.add(new CommandItem("/init", "初始化会话"));
        
        // CLI Agent 命令（仅开发者模式）
        if (developerMode) {
            commands.add(new CommandItem("/bind", "绑定项目路径"));
            commands.add(new CommandItem("/unbind", "解绑项目"));
            commands.add(new CommandItem("/projects", "列出所有项目"));
            commands.add(new CommandItem("/cc", "使用 Claude Code"));
            commands.add(new CommandItem("/oc", "使用 OpenCode"));
            commands.add(new CommandItem("/cli-status", "查看 CLI Agent 状态"));
            commands.add(new CommandItem("/cli-stopall", "停止所有 CLI Agent"));
            commands.add(new CommandItem("/cli-history", "查看历史记录"));
        }
        
        // 按命令名排序
        commands.sort(Comparator.comparing(CommandItem::command));
        
        return commands;
    }
    
    /**
     * 创建弹窗
     */
    private JWindow createWindow() {
        JWindow win = new JWindow(parentFrame);
        win.setAlwaysOnTop(true);
        win.setFocusableWindowState(false);
        
        return win;
    }
    
    /**
     * 设置键盘监听
     */
    private void setupKeyListener() {
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!visible) return;
                
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP -> {
                        e.consume();
                        selectUp();
                    }
                    case KeyEvent.VK_DOWN -> {
                        e.consume();
                        selectDown();
                    }
                    case KeyEvent.VK_TAB, KeyEvent.VK_ENTER -> {
                        e.consume();
                        completeSelected();
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        e.consume();
                        hide();
                    }
                }
            }
            
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_UP 
                    && e.getKeyCode() != KeyEvent.VK_DOWN 
                    && e.getKeyCode() != KeyEvent.VK_TAB 
                    && e.getKeyCode() != KeyEvent.VK_ENTER 
                    && e.getKeyCode() != KeyEvent.VK_ESCAPE) {
                    updateFilter();
                }
            }
        });
        
        // 失焦时隐藏
        inputArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                // 延迟隐藏，避免点击弹窗时立即消失
                SwingUtilities.invokeLater(() -> {
                    if (!window.isFocusableWindow()) {
                        hide();
                    }
                });
            }
        });
    }
    
    /**
     * 更新过滤
     */
    private void updateFilter() {
        String text = inputArea.getText();
        
        if (text == null || !text.startsWith("/")) {
            hide();
            return;
        }
        
        String prefix = text.trim().toLowerCase();
        filteredCommands.clear();
        
        for (CommandItem item : allCommands) {
            if (item.command().toLowerCase().startsWith(prefix)) {
                filteredCommands.add(item);
            }
        }
        
        if (filteredCommands.isEmpty()) {
            hide();
            return;
        }
        
        selectedIndex = 0;
        show();
    }
    
    /**
     * 显示弹窗
     */
    public void show() {
        if (filteredCommands.isEmpty()) {
            return;
        }
        
        rebuildContent();
        positionWindow();
        
        window.setVisible(true);
        visible = true;
    }
    
    /**
     * 隐藏弹窗
     */
    public void hide() {
        window.setVisible(false);
        visible = false;
    }
    
    /**
     * 重建内容
     */
    private void rebuildContent() {
        contentPanel.removeAll();
        
        for (int i = 0; i < filteredCommands.size(); i++) {
            CommandItem item = filteredCommands.get(i);
            JPanel itemPanel = createItemPanel(item, i == selectedIndex);
            contentPanel.add(itemPanel);
        }
        
        contentPanel.revalidate();
        contentPanel.repaint();
        
        // 计算高度
        int visibleCount = Math.min(filteredCommands.size(), MAX_VISIBLE_ITEMS);
        int height = visibleCount * ITEM_HEIGHT + 8;
        window.setSize(POPUP_WIDTH, height);
        
        // 确保选中项可见
        scrollToSelected();
    }
    
    /**
     * 滚动到选中项
     */
    private void scrollToSelected() {
        if (filteredCommands.isEmpty()) return;
        
        int visibleCount = Math.min(filteredCommands.size(), MAX_VISIBLE_ITEMS);
        int firstVisible = 0;
        int lastVisible = visibleCount - 1;
        
        // 如果选中项不在可见范围内，滚动
        if (selectedIndex < firstVisible) {
            int scrollY = selectedIndex * ITEM_HEIGHT;
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(scrollY);
            });
        } else if (selectedIndex > lastVisible) {
            int scrollY = (selectedIndex - visibleCount + 1) * ITEM_HEIGHT;
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(scrollY);
            });
        }
    }
    
    /**
     * 创建单个命令项面板
     */
    private JPanel createItemPanel(CommandItem item, boolean selected) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(selected ? ITEM_BG_SELECTED : ITEM_BG_NORMAL);
        panel.setPreferredSize(new Dimension(POPUP_WIDTH - 16, ITEM_HEIGHT));
        panel.setMaximumSize(new Dimension(POPUP_WIDTH - 16, ITEM_HEIGHT));
        panel.setBorder(new EmptyBorder(4, 8, 4, 8));
        panel.setOpaque(true);
        
        JLabel cmdLabel = new JLabel(item.command());
        cmdLabel.setFont(UiFonts.bodyBold());
        cmdLabel.setForeground(TEXT_PRIMARY);
        
        JLabel descLabel = new JLabel(item.description());
        descLabel.setFont(UiFonts.caption());
        descLabel.setForeground(TEXT_SECONDARY);
        
        panel.add(cmdLabel, BorderLayout.WEST);
        panel.add(descLabel, BorderLayout.EAST);
        
        // 鼠标交互
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                int idx = findItemIndex(item);
                if (idx >= 0 && idx < filteredCommands.size()) {
                    selectedIndex = idx;
                    rebuildContent();
                }
            }
            
            @Override
            public void mouseClicked(MouseEvent e) {
                completeCommand(item.command());
            }
        });
        
        return panel;
    }
    
    /**
     * 查找命令项索引
     */
    private int findItemIndex(CommandItem item) {
        for (int i = 0; i < filteredCommands.size(); i++) {
            if (filteredCommands.get(i).equals(item)) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * 定位弹窗
     */
    private void positionWindow() {
        try {
            Point inputLoc = inputArea.getLocationOnScreen();
            
            int x = inputLoc.x;
            int y = inputLoc.y - window.getHeight() - 4;
            
            // 确保不超出屏幕顶部
            Rectangle screenBounds = getScreenBoundsAt(inputLoc);
            if (y < screenBounds.y) {
                y = screenBounds.y;
            }
            
            window.setLocation(x, y);
        } catch (Exception e) {
            // 如果无法获取位置，使用父窗口位置
            Point parentLoc = parentFrame.getLocation();
            window.setLocation(parentLoc.x + 50, parentLoc.y + 100);
        }
    }
    
    /**
     * 获取屏幕边界
     */
    private Rectangle getScreenBoundsAt(Point point) {
        GraphicsConfiguration gc = parentFrame.getGraphicsConfiguration();
        if (gc != null) {
            return gc.getBounds();
        }
        return new Rectangle(0, 0, 
            Toolkit.getDefaultToolkit().getScreenSize().width,
            Toolkit.getDefaultToolkit().getScreenSize().height);
    }
    
    /**
     * 向上选择
     */
    public void selectUp() {
        if (filteredCommands.isEmpty()) return;
        
        selectedIndex--;
        if (selectedIndex < 0) {
            selectedIndex = filteredCommands.size() - 1;
        }
        rebuildContent();
    }
    
    /**
     * 向下选择
     */
    public void selectDown() {
        if (filteredCommands.isEmpty()) return;
        
        selectedIndex++;
        if (selectedIndex >= filteredCommands.size()) {
            selectedIndex = 0;
        }
        rebuildContent();
    }
    
    /**
     * 补全选中的命令
     */
    public void completeSelected() {
        if (filteredCommands.isEmpty() || selectedIndex >= filteredCommands.size()) {
            return;
        }
        
        CommandItem selected = filteredCommands.get(selectedIndex);
        completeCommand(selected.command());
    }
    
    /**
     * 补全指定命令
     */
    private void completeCommand(String command) {
        inputArea.setText(command + " ");
        inputArea.setCaretPosition(command.length() + 1);
        hide();
        inputArea.requestFocusInWindow();
    }
    
    /**
     * 获取当前选中命令
     */
    public String getSelectedCommand() {
        if (filteredCommands.isEmpty() || selectedIndex >= filteredCommands.size()) {
            return null;
        }
        return filteredCommands.get(selectedIndex).command();
    }
    
    /**
     * 是否可见
     */
    public boolean isVisible() {
        return visible;
    }
    
    /**
     * 更新开发者模式命令
     */
    public void updateDeveloperMode(boolean developerMode) {
        this.allCommands.clear();
        this.allCommands.addAll(buildCommands(developerMode));
    }
}