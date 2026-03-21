---
name: mcp-chrome-install
description: Install mcp-chrome (Chrome MCP Server) to enable AI control of Chrome browser. Use when user wants to install or set up mcp-chrome for browser automation. This skill handles: (1) Load Chrome extension from skill directory, (2) Install mcp-chrome-bridge npm package globally, (3) Register Native Messaging Host, (4) Configure MCP client settings, (5) Cleanup installation files after success.
---

# MCP Chrome Install

Install Chrome MCP Server - enables AI to control your Chrome browser.

## Prerequisites

- Node.js >= 20.0.0
- Chrome/Chromium browser
- Chrome extension zip file (downloaded from https://github.com/hangwin/mcp-chrome/releases)
- 技能本身自带了1.0.0 如果你探测到新版本,询问用户是否下载新版本

## Installation Steps

### Step 1: Prepare Extension Files

The extension zip should be placed in the skill directory:
```
{你当前工作区}/skills/mcp-chrome-install/assets/
```

不用解压
### Step 2: Load Extension in Chrome

**Important**: Chrome requires manual extension loading for security.

Guide the user to:
1. Open Chrome and go to `chrome://extensions/`
2. Enable "Developer mode" (toggle in top right)
3. Click "Load unpacked"
4. Select the extension folder: `{你当前工作区}/skills/mcp-chrome-install/assets/`
5. Verify the extension appears in the list
6. 点击右上方扩展程序,选择刚刚安装的Chrome MCP Server ,点击连接

### Step 3: Install mcp-chrome-bridge

```bash
npm install -g mcp-chrome-bridge
```

### Step 4: Configure MCP Client

Add to MCP client configuration file:

**For javaclawbot**, add to `{工作区上一层}/config.json`:

**Streamable HTTP (Recommended)**:
```json
{
  "mcpServers": {
    "chrome-mcp": {
      "type": "streamableHttp",
      "url": "http://127.0.0.1:12306/mcp"
    }
  }
}
```

```json
{
  "mcpServers": {
    "chrome-mcp": {
      "type": "streamableHttp",
      "url": "http://127.0.0.1:12306/mcp"
    }
  }
}
```

### Step 5: Register Native Messaging Host

```bash
mcp-chrome-bridge register
```

This creates the native messaging host configuration at:

- macOS: `~/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.chromemcp.nativehost.json`

### Step 6: Verify Installation

```bash
# Check if MCP server is running (after clicking Connect in extension)
lsof -i :12306
```

### Step 7: Cleanup (After Success)

After successful installation and verification:

```bash
# Remove the mcp-chrome-install skill
rm -f {你的工作区}/skills/mcp-chrome-install

# Keep the extracted extension folder for Chrome to load
```

## Troubleshooting

### Extension Not Loading

- Ensure the extension folder path is correct
- Check for errors in `chrome://extensions/`
- Try removing and re-loading the extension

### Native Messaging Not Working

- Verify the native messaging host file exists
- Check Chrome's console for native messaging errors
- Re-run `mcp-chrome-bridge register`

### MCP Server Not Starting

- Check if port 12306 is already in use: `lsof -i :12306`
- Kill any existing process: `kill -9 <PID>`
- Restart Chrome and reconnect the extension

## Files

- `assets/` - Place extension zip here before running install