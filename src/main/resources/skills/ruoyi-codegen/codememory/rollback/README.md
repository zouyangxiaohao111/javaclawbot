# Rollback 目录说明

## 目录用途

此目录用于存储代码生成计划的回滚数据，支持两种回滚方式：

### 1. Git 方式（推荐）

适用于 Git 管理的项目，每个步骤完成后自动 commit，回滚时使用 `git reset`。

**优点**：
- 不占用额外磁盘空间
- 回滚精确
- 可查看详细变更历史

### 2. 文件备份方式

适用于非 Git 项目，每个步骤完成后备份修改的文件，回滚时恢复备份。

**优点**：
- 不依赖 Git
- 文件级精确控制

---

## 目录结构

```
rollback/
  ├── plan-template/              # 模板目录（不要删除）
  │   ├── plan-metadata.json      # 计划元数据模板
  │   ├── git-commits.json        # Git 提交记录模板
  │   └── step-1/
  │       └── metadata.json       # 步骤元数据模板
  │
  └── plan-{timestamp}/           # 实际计划目录
      ├── plan-metadata.json      # 计划元数据
      ├── git-commits.json        # Git 提交记录（Git 方式）
      └── step-{n}/               # 步骤备份（文件备份方式）
          ├── files/              # 备份的文件
          └── metadata.json       # 步骤元数据
```

---

## 使用说明

### 创建计划时

1. 检查项目是否是 Git 仓库
2. 创建 `plan-{timestamp}` 目录
3. 初始化 `plan-metadata.json`
4. 根据项目类型选择回滚方式

### 执行步骤时

**Git 方式**：
1. 记录当前 commit hash
2. 执行步骤
3. `git add .`
4. `git commit -m "step N: 步骤名称"`
5. 记录新 commit hash

**文件备份方式**：
1. 创建 `step-{n}/files/` 目录
2. 备份即将修改的文件
3. 执行步骤
4. 记录文件变更列表

### 回滚时

**Git 方式**：
```bash
git reset --hard {targetCommit}
```

**文件备份方式**：
1. 读取步骤元数据
2. 恢复备份文件
3. 删除新创建的文件

---

## 注意事项

1. **不要删除 plan-template 目录**：这是模板目录，用于创建新计划
2. **定期清理**：已完成的计划可以归档或删除
3. **Git 方式优先**：如果项目是 Git 仓库，优先使用 Git 方式