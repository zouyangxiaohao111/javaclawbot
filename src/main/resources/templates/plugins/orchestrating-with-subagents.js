/**
 * JavaClawBot orchestrating-with-subagents 插件（Node.js 版本）
 *
 * 功能：
 * - 读取 orchestrating-with-subagents 技能目录中的 SKILL.md 文件
 * - 生成引导上下文内容注入到系统提示词
 * - 与 plugin/zjkycode.js 协同：zjkycode 提供"如何使用"，orchestrating 提供"如何派 subagent"
 *
 * 说明：
 * - 本插件通过 Node.js 执行（检测到 ES6 模块语法自动切换）
 * - 可使用完整的 Node.js API（fs、path、os 等）
 * - 使用 console.log 输出结果
 *
 * 可用变量：
 * - workspace: 工作区路径（字符串）
 */

import path from 'path';
import fs from 'fs';
import os from 'os';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 复用 frontmatter 提取逻辑（与 zjkycode.js 一致）
const extractAndStripFrontmatter = (content) => {
  const match = content.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) return { frontmatter: {}, content };

  const frontmatterStr = match[1];
  const body = match[2];
  const frontmatter = {};

  for (const line of frontmatterStr.split('\n')) {
    const colonIdx = line.indexOf(':');
    if (colonIdx > 0) {
      const key = line.slice(0, colonIdx).trim();
      const value = line.slice(colonIdx + 1).trim().replace(/^["']|["']$/g, '');
      frontmatter[key] = value;
    }
  }

  return { frontmatter, content: body };
};

// 主逻辑
const homeDir = os.homedir();

// 尝试多个可能的技能目录位置
const possibleSkillDirs = [
    path.join(workspace, 'skills', 'codeskills'),
    path.join(workspace, 'skills', 'orchestrating-with-subagents'),
];

// 查找 SKILL.md 文件
function findSkillFile() {
    for (const skillDir of possibleSkillDirs) {
        const skillFile = path.join(skillDir, 'SKILL.md');
        if (fs.existsSync(skillFile) && fs.statSync(skillFile).isFile()) {
            return skillFile;
        }
    }
    return null;
}

// 生成引导内容
function getBootstrapContent() {
    const skillFile = findSkillFile();
    if (!skillFile) {
        return null;
    }

    try {
        const fullContent = fs.readFileSync(skillFile, 'utf8');
        const { frontmatter, content } = extractAndStripFrontmatter(fullContent);

        // 运行时补充（与 zjkycode.js 风格一致）
        const runtimeSupplement = `**运行说明：**
本插件与zjkycode 系列技能

**触发条件：**
- 用户提出新任务，需要实施代码
- 上下文已变长（>30% 容量）
- 任务需要 3+ 个文件修改，或跨 2+ 个模块
- 你发现自己想"直接动手写代码"

**核心约束（Iron Law）：**
主 agent 走"实现路径"（改源代码 / 写新文件 / 重构）——**永不允许**，
除非决策表明确说"不派"。任何"任务太简单" / "派单浪费 token" 都是合理化借口。

**工具映射：**
当 orchestrating 技能引用你没有的工具时，请替换为你具备的等效工具：
- \`Task\` 工具配合子代理 → 使用 Agent 工具
- \`TodoWrite\` → 内置任务管理工具
- \`Skill\` 工具 → skill 工具
- \`Read\`、\`Write\`、\`Edit\`、\`Bash\` → read_file、write_file、edit_file、exec

当主 agent 收到任何实施类任务时，应自动按 orchestrating 的 4 维决策矩阵决定派/不派。`;

        return `<非常重要>
你拥有 orchestrating-with-subagents 能力。

**重要提示：下方已包含 orchestrating-with-subagents 技能内容。它已经加载完成 —— 你当前正在遵循它。切勿再次使用 skill 工具加载 "orchestrating-with-subagents" —— 那样做是多余的。**

${content}

${runtimeSupplement}
</非常重要>
强制按 orchestrating 4 维决策矩阵执行——简单任务也派 subagent，除非决策表明确说"不派"
`;
    } catch (e) {
        return null;
    }
}

// 输出结果
const bootstrapContent = getBootstrapContent();
if (bootstrapContent) {
    console.log(bootstrapContent);
} else {
    console.log(`<!-- orchestrating-with-subagents 插件：未找到 SKILL.md 文件 -->
<!-- 请确保技能目录存在：
  - ${possibleSkillDirs.join('\n  - ')} -->`);
}
