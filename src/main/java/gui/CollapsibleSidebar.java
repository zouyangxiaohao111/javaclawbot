package gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * 折叠式侧边栏
 * 
 * 包含展开/折叠按钮和工具按钮
 */
public class CollapsibleSidebar extends JPanel {
    
    private static final int COLLAPSED_WIDTH = 36;
    private static final int EXPANDED_WIDTH = 50;
    
    private final JButton toggleButton;
    private final JButton toolsButton;
    private boolean expanded = false;
    
    // 颜色定义
    private static final Color SIDEBAR_BG = new Color(245, 245, 247);
    private static final Color BORDER_COLOR = new Color(229, 229, 234);
    private static final Color BUTTON_BG = new Color(0, 0, 0, 0);
    private static final Color BUTTON_HOVER_BG = new Color(0, 0, 0, 15);
    private static final Color TEXT_COLOR = new Color(99, 99, 102);
    
    public CollapsibleSidebar() {
        setLayout(new BorderLayout());
        setBackground(SIDEBAR_BG);
        setPreferredSize(new Dimension(COLLAPSED_WIDTH, Integer.MAX_VALUE));
        setMinimumSize(new Dimension(COLLAPSED_WIDTH, 0));
        setMaximumSize(new Dimension(EXPANDED_WIDTH, Integer.MAX_VALUE));
        
        // 添加右侧分隔线
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER_COLOR));
        
        // 创建按钮面板
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setOpaque(false);
        buttonPanel.setBorder(new EmptyBorder(8, 4, 8, 4));
        
        // 展开/折叠按钮
        toggleButton = createIconButton(">");
        toggleButton.setToolTipText("展开侧边栏");
        toggleButton.addActionListener(e -> toggle());
        buttonPanel.add(toggleButton);
        buttonPanel.add(Box.createVerticalStrut(8));

        // 工具按钮（初始隐藏）
        toolsButton = createIconButton("设置");
        toolsButton.setToolTipText("MCP 工具管理");
        toolsButton.setVisible(false);
        buttonPanel.add(toolsButton);
        
        add(buttonPanel, BorderLayout.NORTH);
    }
    
    /**
     * 创建图标按钮
     */
    private JButton createIconButton(String icon) {
        JButton btn = new JButton(icon);
        btn.setFont(UiFonts.body());
        btn.setForeground(TEXT_COLOR);
        btn.setBackground(BUTTON_BG);
        btn.setBorder(new EmptyBorder(4, 4, 4, 4));
        btn.setPreferredSize(new Dimension(28, 28));
        btn.setMinimumSize(new Dimension(28, 28));
        btn.setMaximumSize(new Dimension(28, 28));
        btn.setFocusable(false);
        btn.setOpaque(false);
        btn.setHorizontalAlignment(SwingConstants.CENTER);
        
        // 悬停效果
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                btn.setBackground(BUTTON_HOVER_BG);
                btn.setOpaque(true);
            }
            
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                btn.setBackground(BUTTON_BG);
                btn.setOpaque(false);
            }
        });
        
        return btn;
    }
    
    /**
     * 切换展开/折叠
     */
    public void toggle() {
        setExpanded(!expanded);
    }
    
    /**
     * 设置展开状态
     */
    public void setExpanded(boolean expanded) {
        this.expanded = expanded;

        if (expanded) {
            toggleButton.setText("<");
            toggleButton.setToolTipText("折叠侧边栏");
            toolsButton.setVisible(true);
            setPreferredSize(new Dimension(EXPANDED_WIDTH, Integer.MAX_VALUE));
        } else {
            toggleButton.setText(">");
            toggleButton.setToolTipText("展开侧边栏");
            toolsButton.setVisible(false);
            setPreferredSize(new Dimension(COLLAPSED_WIDTH, Integer.MAX_VALUE));
        }
        
        revalidate();
        repaint();
        
        // 通知父容器重新布局
        Container parent = getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }
    
    /**
     * 获取当前宽度
     */
    public int getCurrentWidth() {
        return expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
    }
    
    /**
     * 是否展开
     */
    public boolean isExpanded() {
        return expanded;
    }
    
    /**
     * 设置工具按钮点击监听器
     */
    public void setToolsButtonListener(ActionListener listener) {
        toolsButton.addActionListener(listener);
    }
}