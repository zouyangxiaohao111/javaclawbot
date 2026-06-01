# Example 3: 重构 FileBackupManager

**场景**：FileBackupManager.java 已经 800 行，需要拆分为多个类。

## 1. 用户需求

> `src/main/java/utils/FileBackupManager.java` 现在 800 行，职责混乱（备份/恢复/清理/历史）。请拆分为多个职责单一的类。

## 2. 编排器流程

### 2.1 写 plan

`plan/task_plan.md`：

```markdown
# Task Plan: 重构 FileBackupManager

## Goal
将 FileBackupManager 拆分为 BackupService/RestoreService/CleanupService/HistoryService

## Current Phase
Phase 1: 准备

## Phases

### Phase 1: 准备 (status: in_progress)
- [ ] 梳理 FileBackupManager 的所有公共方法
- [ ] 分类：备份/恢复/清理/历史
- [ ] 写重构合约（plan/contracts/refactor-1.md）

### Phase 2: 拆分 (status: pending)
- [ ] 创建 BackupService.java（备份相关）
- [ ] 创建 RestoreService.java（恢复相关）
- [ ] 创建 CleanupService.java（清理相关）
- [ ] 创建 HistoryService.java（历史相关）

### Phase 3: 集成 (status: pending)
- [ ] 删除 FileBackupManager.java（或保留为 facade）
- [ ] 更新所有调用方
- [ ] 写测试

## Key Decisions
- 保留 FileBackupManager 作为 facade（向后兼容）
- 内部实现委托给 4 个新 service

## Session Handoff
- 当前进度: Phase 1
```

### 2.2 派 Phase 1 任务

context packet：

```markdown
# Subagent Task Brief: prepare-refactor

## 任务目标（Goal）
梳理 FileBackupManager 的所有公共方法，按职责分类。

## 范围（Scope）

### 必须做
- [ ] 读 FileBackupManager.java 全部
- [ ] 列出所有公共方法
- [ ] 按 4 类分类：备份/恢复/清理/历史
- [ ] 写 findings/prepare-1.md（分类清单）

### 不要做
- ❌ 不要修改任何代码
- ❌ 不要建议实现（只做分类）

## 成功标准（Done When）
- [ ] findings/prepare-1.md 包含完整方法清单
- [ ] 每个方法标注：分类 + 复杂度（高/中/低）
- [ ] findings/prepare-1.md 给出拆分建议（每个 service 包含哪些方法）

## 相关文件（Required Reading）
- `src/main/java/utils/FileBackupManager.java`

## 返回契约
- 写 `findings/prepare-1.md`

## 验证命令
- 无

## 触发相关 skill
- 无
```

### 2.3 subagent 实施

写 `findings/prepare-1.md`：

```markdown
# Findings: prepare-refactor

## 方法分类

### BackupService（备份相关）
- `createBackup(File)` - 高
- `incrementalBackup(File)` - 中
- `validateBackup(File)` - 低

### RestoreService（恢复相关）
- `restoreFromBackup(String)` - 高
- `verifyBackup(String)` - 中
- `listRestorableBackups()` - 低

### CleanupService（清理相关）
- `cleanupOldBackups(int)` - 中
- `cleanupOrphanedFiles()` - 中

### HistoryService（历史相关）
- `getBackupHistory()` - 低
- `getBackupByTimestamp(long)` - 低
- `archiveHistory()` - 中

## 拆分建议

| Service | 方法数 | 复杂度 |
|---|---|---|
| BackupService | 3 | 高 |
| RestoreService | 3 | 高 |
| CleanupService | 2 | 中 |
| HistoryService | 3 | 低 |

**保留 FileBackupManager 作为 facade**，内部委托给 4 个 service。这样外部 API 不变。
```

### 2.4 派 Phase 2 任务

主 agent 读 findings/prepare-1.md，进入 Phase 2。

派 4 个并行 subagent（每个 service 一个）：

```python
load_skill("dispatching-parallel-agents")
```

因为 4 个 service **独立**（不共享文件），可以并行。

context packet（4 个类似，以 BackupService 为例）：

```markdown
# Subagent Task Brief: create-BackupService

## 任务目标（Goal）
从 FileBackupManager 提取备份相关方法到 BackupService。

## 范围（Scope）

### 必须做
- [ ] 创建 `src/main/java/utils/BackupService.java`
- [ ] 迁移 3 个方法：createBackup/incrementalBackup/validateBackup
- [ ] 保留方法签名（外部 API 不变）
- [ ] 写单元测试

### 不要做
- ❌ 不要修改其他 service
- ❌ 不要删除 FileBackupManager
- ❌ 不要改变方法签名

## 成功标准（Done When）
- [ ] BackupService.java 存在
- [ ] 3 个方法签名不变
- [ ] mvn test 通过
- [ ] mvn compile 通过

## 相关文件（Required Reading）
- `findings/prepare-1.md` — 拆分方案
- `src/main/java/utils/FileBackupManager.java:42-78` — 待迁移的代码

## 约束
- 保留 SLF4J logging
- 保留现有 JavaDoc

## 返回契约
- `findings/impl-BackupService.md`
- `progress/impl-BackupService-progress.md`

## 验证命令
- `mvn compile`
- `mvn test -Dtest=BackupServiceTest`

## 触发相关 skill
- test-driven-development
- verification-before-completion
```

4 个 subagent 并行：
- impl-BackupService
- impl-RestoreService
- impl-CleanupService
- impl-HistoryService

### 2.5 收结果

4 个 subagent 都返回 DONE。

主 agent 读 4 个 findings/，更新 plan。

### 2.6 Phase 3：集成

派另一个 subagent 更新 FileBackupManager（作为 facade）：

context packet：
```markdown
# Subagent Task Brief: refactor-FileBackupManager-facade

## 任务目标
将 FileBackupManager 改为 facade，内部委托给 4 个新 service。

## 范围

### 必须做
- [ ] 修改 FileBackupManager.java，所有公共方法改为委托：
  ```java
  public File createBackup(File f) { return backupService.createBackup(f); }
  ```
- [ ] 删除 FileBackupManager 中已迁移的代码
- [ ] 保留方法签名（外部 API 完全兼容）
- [ ] 跑全量测试，验证无 regression

### 不要做
- ❌ 不要改变方法签名
- ❌ 不要删除 FileBackupManager（保留为 facade）

## 成功标准
- [ ] mvn test 全量通过
- [ ] 没有任何调用方需要修改

## 返回契约
- `findings/refactor-facade.md`
```

### 2.7 收尾

- 派 final code review subagent
- 派 verification-before-completion subagent
- 更新 CHANGELOG.md

## 关键要点

1. **重构也派 subagent**——不亲自重构
2. **独立子任务并行**——用 dispatching-parallel-agents
3. **API 兼容是核心**——保留 facade
4. **测试不可少**——每个 service 写测试
