# 深度防御验证

## 概述

当你修复一个由无效数据引起的 bug 时，在一个地方添加验证似乎就足够了。但单一检查可能被不同的代码路径、重构或模拟绕过。

**核心原则：** 在数据经过的每一层都进行验证。让 bug 在结构上不可能发生。

## 为什么需要多层

单一验证："我们修复了 bug"
多层验证："我们让 bug 不可能发生"

不同层捕获不同情况：
- 入口验证捕获大多数 bug
- 业务逻辑捕获边缘情况
- 环境防护防止特定上下文的危险
- 调试日志在其他层失败时提供帮助

## 四层防御

### 第 1 层：入口点验证
**目的：** 在 API 边界拒绝明显无效的输入

```typescript
function createProject(name: string, workingDirectory: string) {
  if (!workingDirectory || workingDirectory.trim() === '') {
    throw new Error('workingDirectory cannot be empty');
  }
  if (!existsSync(workingDirectory)) {
    throw new Error(`workingDirectory does not exist: ${workingDirectory}`);
  }
  if (!statSync(workingDirectory).isDirectory()) {
    throw new Error(`workingDirectory is not a directory: ${workingDirectory}`);
  }
  // ... proceed
}
```

### 第 2 层：业务逻辑验证
**目的：** 确保数据对此操作有意义

```typescript
function initializeWorkspace(projectDir: string, sessionId: string) {
  if (!projectDir) {
    throw new Error('projectDir required for workspace initialization');
  }
  // ... proceed
}
```

### 第 3 层：环境防护
**目的：** 在特定上下文中防止危险操作

```typescript
async function gitInit(directory: string) {
  // In tests, refuse git init outside temp directories
  if (process.env.NODE_ENV === 'test') {
    const normalized = normalize(resolve(directory));
    const tmpDir = normalize(resolve(tmpdir()));

    if (!normalized.startsWith(tmpDir)) {
      throw new Error(
        `Refusing git init outside temp dir during tests: ${directory}`
      );
    }
  }
  // ... proceed
}
```

### 第 4 层：调试埋点
**目的：** 捕获上下文用于取证

```typescript
async function gitInit(directory: string) {
  const stack = new Error().stack;
  logger.debug('About to git init', {
    directory,
    cwd: process.cwd(),
    stack,
  });
  // ... proceed
}
```

## 应用模式

当你发现一个 bug：

1. **追踪数据流** - 错误值从哪里来？在哪里使用？
2. **映射所有检查点** - 列出数据经过的每个点
3. **在每层添加验证** - 入口、业务、环境、调试
4. **测试每层** - 尝试绕过第 1 层，验证第 2 层能捕获

## 会话示例

Bug：空的 `projectDir` 导致在源代码目录执行 `git init`

**数据流：**
1. 测试设置 → 空字符串
2. `Project.create(name, '')`
3. `WorkspaceManager.createWorkspace('')`
4. `git init` 在 `process.cwd()` 中运行

**添加的四层：**
- 第 1 层：`Project.create()` 验证非空/存在/可写
- 第 2 层：`WorkspaceManager` 验证 projectDir 非空
- 第 3 层：`WorktreeManager` 在测试中拒绝临时目录外的 git init
- 第 4 层：git init 前的堆栈跟踪日志

**结果：** 全部 1847 个测试通过，bug 无法复现

## 关键洞察

所有四层都是必要的。在测试期间，每层都捕获了其他层遗漏的 bug：
- 不同代码路径绕过了入口验证
- 模拟绕过了业务逻辑检查
- 不同平台的边缘情况需要环境防护
- 调试日志识别了结构性误用

**不要止步于一个验证点。** 在每一层都添加检查。