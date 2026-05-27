---
name: using-agency-agents
description: Use when needing specialized domain expertise - load agency agent skills for engineering, design, security, DevOps, and more. Establishes how to find and invoke agency-agents skills.
enable: false
---

# 使用 Agency Agents 技能包

Agency Agents 技能包提供 37 个专业领域代理技能（29 工程 + 8 设计），按需加载为当前会话注入领域专家知识。

## 技能位置

```
${workspace}/skills/agency-agents/
├── engineering/          ← 29 个工程技能
│   ├── senior-developer/SKILL.md
│   ├── code-reviewer/SKILL.md
│   ├── software-architect/SKILL.md
│   └── ...
└── design/               ← 8 个设计技能
    ├── ui-designer/SKILL.md
    ├── ux-researcher/SKILL.md
    └── ...
```

## 如何使用

使用 `skill` 工具加载指定技能：

```
agency-agents/engineering/senior-developer
agency-agents/engineering/code-reviewer
agency-agents/design/ui-designer
```

**重要**：所有 agency-agents 技能加载都需要加前缀 `agency-agents/`。

## 工程技能清单（29）

### 架构与系统设计
| 技能 | 加载路径 |
|------|---------|
| Software Architect | `agency-agents/engineering/software-architect` |
| Backend Architect | `agency-agents/engineering/backend-architect` |
| Autonomous Optimization Architect | `agency-agents/engineering/autonomous-optimization-architect` |

### 开发
| 技能 | 加载路径 |
|------|---------|
| Senior Developer | `agency-agents/engineering/senior-developer` |
| Frontend Developer | `agency-agents/engineering/frontend-developer` |
| Mobile App Builder | `agency-agents/engineering/mobile-app-builder` |
| Rapid Prototyper | `agency-agents/engineering/rapid-prototyper` |
| CMS Developer | `agency-agents/engineering/cms-developer` |
| Filament Optimization Specialist | `agency-agents/engineering/filament-optimization-specialist` |
| Minimal Change Engineer | `agency-agents/engineering/minimal-change-engineer` |

### 数据与 AI
| 技能 | 加载路径 |
|------|---------|
| AI Engineer | `agency-agents/engineering/ai-engineer` |
| Data Engineer | `agency-agents/engineering/data-engineer` |
| AI Data Remediation Engineer | `agency-agents/engineering/ai-data-remediation-engineer` |
| Database Optimizer | `agency-agents/engineering/database-optimizer` |

### DevOps 与可靠性
| 技能 | 加载路径 |
|------|---------|
| DevOps Automator | `agency-agents/engineering/devops-automator` |
| SRE | `agency-agents/engineering/sre` |
| Incident Response Commander | `agency-agents/engineering/incident-response-commander` |
| Git Workflow Master | `agency-agents/engineering/git-workflow-master` |

### 安全
| 技能 | 加载路径 |
|------|---------|
| Security Engineer | `agency-agents/engineering/security-engineer` |
| Threat Detection Engineer | `agency-agents/engineering/threat-detection-engineer` |

### 区块链与嵌入式
| 技能 | 加载路径 |
|------|---------|
| Solidity Smart Contract Engineer | `agency-agents/engineering/solidity-smart-contract-engineer` |
| Embedded Firmware Engineer | `agency-agents/engineering/embedded-firmware-engineer` |

### 集成与工具
| 技能 | 加载路径 |
|------|---------|
| Feishu Integration Developer | `agency-agents/engineering/feishu-integration-developer` |
| WeChat Mini Program Developer | `agency-agents/engineering/wechat-mini-program-developer` |
| Email Intelligence Engineer | `agency-agents/engineering/email-intelligence-engineer` |
| Voice AI Integration Engineer | `agency-agents/engineering/voice-ai-integration-engineer` |

### 质量与文档
| 技能 | 加载路径 |
|------|---------|
| Code Reviewer | `agency-agents/engineering/code-reviewer` |
| Codebase Onboarding Engineer | `agency-agents/engineering/codebase-onboarding-engineer` |
| Technical Writer | `agency-agents/engineering/technical-writer` |

## 设计技能清单（8）

### UI/UX 设计
| 技能 | 加载路径 |
|------|---------|
| UI Designer | `agency-agents/design/ui-designer` |
| UX Architect | `agency-agents/design/ux-architect` |
| UX Researcher | `agency-agents/design/ux-researcher` |

### 品牌与视觉
| 技能 | 加载路径 |
|------|---------|
| Brand Guardian | `agency-agents/design/brand-guardian` |
| Visual Storyteller | `agency-agents/design/visual-storyteller` |
| Whimsy Injector | `agency-agents/design/whimsy-injector` |

### 专项
| 技能 | 加载路径 |
|------|---------|
| Image Prompt Engineer | `agency-agents/design/image-prompt-engineer` |
| Inclusive Visuals Specialist | `agency-agents/design/inclusive-visuals-specialist` |

## 使用场景

### 何时加载工程技能
- 需要特定领域专家视角时（如代码审查 → `code-reviewer`）
- 架构设计决策时（如系统设计 → `software-architect`）
- 安全相关任务时（如漏洞评估 → `security-engineer`）
- DevOps 任务时（如 CI/CD → `devops-automator`）

### 何时加载设计技能
- UI/UX 设计任务时（如界面设计 → `ui-designer`）
- 品牌建设时（如品牌策略 → `brand-guardian`）
- 视觉内容创作时（如 AI 图像提示 → `image-prompt-engineer`）

## 与 zjkycode 的关系

Agency Agents 是**领域专家**技能，zjkycode 是**工作流程**技能。可以同时使用：

- zjkycode 控制**怎么做**（brainstorming → planning → execution）
- agency-agents 提供**谁来做**（以哪个专家身份执行）

示例：构建前端功能时，先加载 `agency-agents/engineering/frontend-developer` 注入领域知识，再按 zjkycode 的 brainstorming → writing-plans 流程执行。
