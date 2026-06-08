//package gui.ui;
//
//import gui.ui.components.SessionTabBar;
//import gui.ui.components.TabItem;
//import javafx.scene.layout.VBox;
//import org.junit.Before;
//import org.junit.Test;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//
//import static org.mockito.Mockito.*;
//
///**
// * SessionTabManager 单元测试
// */
//public class SessionTabManagerTest {
//
//    @Mock
//    private SessionTabBar tabBar;
//
//    @Mock
//    private BackendBridge backendBridge;
//
//    @Mock
//    private VBox chatArea;
//
//    private SessionTabManager sessionTabManager;
//
//    @Before
//    public void setUp() {
//        MockitoAnnotations.initMocks(this);
//        sessionTabManager = new SessionTabManager(tabBar, backendBridge, chatArea);
//    }
//
//    /**
//     * 测试：当标签数达到上限时，switchToSession应该显示提示而不是加载会话
//     *
//     * 预期行为：
//     * 1. 当tabBar.getTabCount() >= getMaxConcurrent()时
//     * 2. 调用switchToSession应该显示提示
//     * 3. 不应该调用loadSessionInCurrentTab
//     */
//    @Test
//    public void switchToSession_WhenTabCountAtMax_ShouldShowToastAndNotLoadSession() {
//        // Arrange: 设置标签数达到上限（4个）
//        when(tabBar.getTabCount()).thenReturn(4);
//        String sessionId = "test-session-123";
//
//        // Act: 调用switchToSession
//        sessionTabManager.switchToSession(sessionId);
//
//        // Assert: 验证显示了提示（通过检查showConcurrencyLimitToast被调用）
//        // 注意：由于showConcurrencyLimitToast是私有方法，我们需要通过其他方式验证
//        // 这里我们验证没有调用loadSessionInCurrentTab（通过检查backendBridge的方法）
//
//        // 验证没有调用resumeSession（loadSessionInCurrentTab会调用这个）
//        verify(backendBridge, never()).resumeSession(anyString(), anyString());
//
//        // 验证没有调用setActiveTab（loadSessionInCurrentTab会调用这个）
//        verify(backendBridge, never()).setActiveTab(anyString());
//
//        // 验证没有调用getSessionManager（loadSessionInCurrentTab会调用这个）
//        verify(backendBridge, never()).getSessionManager();
//    }
//
//    /**
//     * 测试：当标签数未达到上限时，switchToSession应该创建新标签
//     */
//    @Test
//    public void switchToSession_WhenTabCountBelowMax_ShouldCreateNewTab() {
//        // Arrange: 设置标签数未达到上限
//        when(tabBar.getTabCount()).thenReturn(3);
//        String sessionId = "test-session-123";
//
//        // Mock tabBar.addTab返回一个TabItem
//        TabItem mockTabItem = mock(TabItem.class);
//        when(tabBar.addTab(anyString(), anyString())).thenReturn(mockTabItem);
//
//        // Act: 调用switchToSession
//        sessionTabManager.switchToSession(sessionId);
//
//        // Assert: 验证创建了新标签
//        verify(tabBar).addTab(anyString(), anyString());
//
//        // 验证调用了resumeSession
//        verify(backendBridge).resumeSession(anyString(), eq(sessionId));
//    }
//}