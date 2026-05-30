/**
 * JavaClawBot agency-agents 插件（Node.js 版本）
 *
 * 功能：
 * - 读取 agency-agents 技能目录中的 using-agency-agents/SKILL.md 文件
 * - 生成引导上下文内容注入到系统提示词，列出所有可用的代理技能
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

// 尝试多个可能的技能目录位置
const possibleSkillDirs = [
    path.join(workspace, 'skills', 'agency-agents'),
    path.join(homeDir, '.javaclawbot', 'workspace', 'skills', 'agency-agents')
];

// 查找 using-agency-agents SKILL.md 文件
function findSkillFile() {
    for (const skillDir of possibleSkillDirs) {
        const skillFile = path.join(skillDir, 'using-agency-agents', 'SKILL.md');
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

        return `<agency-agents-skill-pack>
## Agency Agents 技能包 — 已激活

${content}

**技能位置：**
agency-agents 技能位于 \`${workspace}/skills/agency-agents/\`
该技能包下面的所有技能加载都需要加前缀，示例: agency-agents/engineering/senior-developer
使用原生 skill 工具来列出和加载技能。
</agency-agents-skill-pack>`;
    } catch (e) {
        return null;
    }
}

// 输出结果
const bootstrapContent = getBootstrapContent();
if (bootstrapContent) {
    console.log(bootstrapContent);
} else {
    console.log(`<!-- agency-agents 插件：未找到 using-agency-agents/SKILL.md 文件 -->
<!-- 请确保技能目录存在：
  - ${possibleSkillDirs.join('\n  - ')} -->`);
}
