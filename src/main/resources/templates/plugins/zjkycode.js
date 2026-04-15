/**
 * JavaClawBot zjkycode 插件（Node.js 版本）
 *
 * 功能：
 * - 读取 zjkycode 技能目录中的 SKILL.md 文件
 * - 生成引导上下文内容注入到系统提示词
 *
 * 说明：
 * - 本插件通过 Node.js 执行（检测到 ES6 模块语法自动切换）
 * - 可使用完整的 Node.js API（fs、path、os 等）
 * - 使用 console.log 输出结果，或调用 setResult(value)
 *
 * 可用变量：
 * - workspace: 工作区路径（字符串）
 */

import path from 'path';
import fs from 'fs';
import os from 'os';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 简单的 frontmatter 提取
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
const configDir = path.join(homeDir, '.javaclawbot');

// 尝试多个可能的技能目录位置
const possibleSkillDirs = [
    path.join(workspace, 'skills', 'zjkycode'),
    path.join(homeDir, '.javaclawbot',"workspace", 'skills', 'zjkycode')
];

// 查找 using-zjkycode SKILL.md 文件
function findSkillFile() {
    for (const skillDir of possibleSkillDirs) {
        const skillFile = path.join(skillDir, 'using-zjkycode', 'SKILL.md');
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
        const { content } = extractAndStripFrontmatter(fullContent);

        const toolMapping = `** 工具映射：**
当技能引用你没有的工具时，请替换为 你具备的 等效工具：
- \`TodoWrite\` → 内置任务管理工具
- \`Task\` 工具配合子代理 → 使用 spawn_sessions 工具
- \`Skill\` 工具 → skill 工具
- \`Read\`、\`Write\`、\`Edit\`、\`Bash\` → read_file、write_file、edit_file、exec

**技能位置：**
zjkycode 技能位于 \`${workspace}/skills/zjkycode/\`
该技能包下面的所有技能加载都需要加前缀,示例: zjkycode/brainstorming
使用  原生 skill 工具来列出和加载技能。`;

        return `<非常重要>
你拥有 zjkycode 能力。

**重要提示：下方已包含 using-zjkycode 技能内容。它已经加载完成 —— 你当前正在遵循它。切勿再次使用 skill 工具加载 "using-zjkycode" —— 那样做是多余的。**

${content}

${toolMapping}
</非常重要>
强制按照zjkycode流程执行
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
    console.log(`<!-- zjkycode 插件：未找到 using-zjkycode/SKILL.md 文件 -->
<!-- 请确保技能目录存在：
  - ${possibleSkillDirs.join('\n  - ')} -->`);
}