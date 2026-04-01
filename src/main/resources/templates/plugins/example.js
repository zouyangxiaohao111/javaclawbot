/**
 * JavaScript 插件示例
 *
 * 插件执行方式（自动检测）：
 * - 如果脚本包含 ES6 模块语法（import/export）或 Node.js API，通过 Node.js 执行
 * - 如果脚本不包含这些语法，通过 GraalJS（嵌入式 JS 引擎）执行
 * - .mjs / .cjs 扩展名强制使用 Node.js 执行
 *
 * 可用变量：
 * - workspace: 工作区路径（字符串）
 *
 * 返回结果方式：
 * - GraalJS: setResult(value) 或设置 result 变量
 * - Node.js: console.log(value) 或 setResult(value)
 *
 * 配置示例（config.json）：
 * {
 *   "plugins": {
 *     "items": {
 *       "example": { "enabled": true, "priority": 10 },
 *       "zjkycode": { "enabled": false, "priority": 20 }
 *     }
 *   }
 * }
 */

// GraalJS 方式（推荐用于简单脚本）
setResult("这是来自 JS 插件的示例输出。\n工作区路径: " + workspace);

// Node.js 方式示例（用于需要 Node.js API 的脚本）
// import fs from 'fs';
// import path from 'path';
//
// const content = fs.readFileSync(path.join(workspace, 'README.md'), 'utf8');
// console.log("README 内容:\n" + content.slice(0, 500));