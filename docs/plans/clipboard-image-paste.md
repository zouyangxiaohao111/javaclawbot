# ChatPage 剪贴板图片粘贴修复计划

## 问题描述

ChatPage 的输入区域（ChatInput 中的 TextArea）不支持粘贴剪贴板中的图片。用户从截图工具或浏览器复制图片后，在输入框中 Ctrl+V / Cmd+V 不会将图片加入附件列表。

## 根因分析

`ChatInput` 构造函数（第 223 行）虽然注册了 `KEY_PRESSED` 事件过滤器，但只处理了 Enter（发送）/ Esc（停止）逻辑，没有拦截粘贴事件去检测剪贴板图片。

JavaFX 的 `TextArea` 原生只处理文本粘贴，剪贴板中的图像数据会被忽略。

## 数据流

```
用户 Ctrl+V / Cmd+V
  → ChatInput (KEY_PRESSED event filter)
    → 读取 Clipboard.getSystemClipboard()
      → 有图片？
        ├─ 是 → 保存为 PNG 到 tmp/javaclawbot/clipboard/
        │       → 调用 handleFile(path)
        │       → 图片加入 imagePaths 列表
        │       → 显示缩略图预览
        │       → consume() 事件（阻止 TextArea 处理）
        └─ 否 → 不做拦截，TextArea 正常处理文本粘贴
```

## 修改范围

**仅修改 1 个文件**：`src/main/java/gui/ui/components/ChatInput.java`

### 修改点 1：构造函数中添加粘贴事件拦截

在 `inputArea.addEventFilter(KeyEvent.KEY_PRESSED, ...)` 现有逻辑中，增加粘贴检测：

```java
// 在 KEY_PRESSED handler 中，Enter/Esc 处理之前添加：
if (isPasteShortcut(e)) {
    if (handleClipboardImagePaste()) {
        e.consume();
        return;
    }
    // 无图片则放行，让 TextArea 正常处理文本粘贴
}
```

- `isPasteShortcut(e)`: macOS 检测 `META + V`，Windows/Linux 检测 `CTRL + V`

### 修改点 2：新增方法 `handleClipboardImagePaste()`

```java
private boolean handleClipboardImagePaste() {
    Clipboard clipboard = Clipboard.getSystemClipboard();
    if (!clipboard.hasImage()) return false;

    Image fxImage = clipboard.getImage();
    if (fxImage == null) return false;

    // 保存到临时目录
    Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "javaclawbot", "clipboard");
    Files.createDirectories(tmpDir);
    Path tmpFile = tmpDir.resolve("clipboard_" + System.currentTimeMillis() + ".png");

    // JavaFX Image → BufferedImage → PNG file
    BufferedImage buffered = javafxImageToBuffered(fxImage);
    ImageIO.write(buffered, "png", tmpFile.toFile());

    // 复用现有 handleFile 流程：加入 imagePaths + 显示缩略图
    handleFile(tmpFile);
    return true;
}
```

### 修改点 3：新增辅助方法 `javafxImageToBuffered()`

JavaFX `Image` → AWT `BufferedImage` 转换：

```java
private static BufferedImage javafxImageToBuffered(Image fxImage) {
    int w = (int) fxImage.getWidth();
    int h = (int) fxImage.getHeight();
    BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    // 逐像素复制
    PixelReader reader = fxImage.getPixelReader();
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            buffered.setRGB(x, y, reader.getArgb(x, y));
        }
    }
    return buffered;
}
```

### 修改点 4：新增 import

```java
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.image.PixelReader;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
```

## 边界情况处理

| 场景 | 行为 |
|------|------|
| 剪贴板无内容 | 正常粘贴（无操作） |
| 剪贴板只有文本 | 放行，TextArea 正常插入文本 |
| 剪贴板只有图片 | 保存为 PNG，加入附件预览 |
| 剪贴板同时有文本+图片 | 图片加入附件预览，文本放行给 TextArea |
| 剪贴板图片读取失败 | 静默忽略，放行给 TextArea |
| 连续粘贴多张图片 | 每次生成唯一文件名（时间戳），各自独立 |

## 验证方法

1. 用系统截图工具截图（此时图片在剪贴板）
2. 点击 ChatInput 输入框
3. 按 Ctrl+V（Windows）或 Cmd+V（macOS）
4. 预期：图片缩略图出现在输入框上方预览行
5. 输入文字后点发送，图片随消息发送

## 后续考虑的增强（本次不实现）

- 粘贴图片的临时文件清理策略（当前依赖 OS 临时目录回收）
- 粘贴 GIF/动图支持
- 支持从浏览器直接复制图片 URL
