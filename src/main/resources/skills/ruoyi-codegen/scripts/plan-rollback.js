#!/usr/bin/env node

/**
 * 计划回滚脚本
 * 用于回滚代码生成计划
 * 
 * 使用方式：
 * node plan-rollback.js --plan=plan-20260310-174300 --step=3
 * 
 * 功能：
 * 1. 读取回滚元数据
 * 2. 执行回滚（Git 或文件备份方式）
 * 3. 更新计划状态
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// 命令行参数解析
function parseArgs() {
  const args = process.argv.slice(2);
  const config = {
    planId: '',
    targetStep: 0
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--plan=')) {
      config.planId = arg.substring(7);
    } else if (arg.startsWith('--step=')) {
      config.targetStep = parseInt(arg.substring(7));
    }
  }

  return config;
}

// 检查是否是 Git 仓库
function isGitRepository(projectPath) {
  try {
    execSync('git rev-parse --git-dir', { cwd: projectPath, stdio: 'pipe' });
    return true;
  } catch (error) {
    return false;
  }
}

// Git 方式回滚
function rollbackWithGit(projectPath, planMetadata, targetStep) {
  console.log('🔄 使用 Git 方式回滚...');
  
  // 读取 git-commits.json
  const gitCommitsPath = path.join(__dirname, '../memory/rollback', planMetadata.planId, 'git-commits.json');
  
  if (!fs.existsSync(gitCommitsPath)) {
    console.error('❌ 找不到 git-commits.json');
    return false;
  }
  
  const gitCommits = JSON.parse(fs.readFileSync(gitCommitsPath, 'utf-8'));
  
  // 找到目标步骤的 commit
  const targetCommit = gitCommits.commits.find(c => c.step === targetStep);
  
  if (!targetCommit) {
    console.error(`❌ 找不到步骤 ${targetStep} 的 commit`);
    return false;
  }
  
  try {
    // 执行 git reset
    console.log(`📌 回滚到 commit: ${targetCommit.commitHash}`);
    execSync(`git reset --hard ${targetCommit.commitHash}`, { cwd: projectPath, stdio: 'inherit' });
    console.log('✅ Git 回滚成功');
    return true;
  } catch (error) {
    console.error('❌ Git 回滚失败:', error.message);
    return false;
  }
}

// 文件备份方式回滚
function rollbackWithFiles(projectPath, planMetadata, targetStep) {
  console.log('🔄 使用文件备份方式回滚...');
  
  const rollbackDir = path.join(__dirname, '../memory/rollback', planMetadata.planId);
  
  // 从后往前恢复文件
  for (let step = planMetadata.steps.length; step > targetStep; step--) {
    const stepDir = path.join(rollbackDir, `step-${step}`);
    const metadataPath = path.join(stepDir, 'metadata.json');
    
    if (!fs.existsSync(metadataPath)) {
      console.log(`⚠️ 步骤 ${step} 没有备份，跳过`);
      continue;
    }
    
    const metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf-8'));
    
    console.log(`📌 恢复步骤 ${step} 的文件...`);
    
    // 恢复文件
    metadata.files.forEach(file => {
      const targetPath = path.join(projectPath, file.path);
      const backupPath = path.join(stepDir, file.backupPath);
      
      if (file.action === 'create') {
        // 删除新创建的文件
        if (fs.existsSync(targetPath)) {
          fs.unlinkSync(targetPath);
          console.log(`  ❌ 删除: ${file.path}`);
        }
      } else if (file.action === 'modify' || file.action === 'delete') {
        // 恢复备份文件
        if (fs.existsSync(backupPath)) {
          fs.copyFileSync(backupPath, targetPath);
          console.log(`  ✅ 恢复: ${file.path}`);
        }
      }
    });
  }
  
  console.log('✅ 文件备份回滚成功');
  return true;
}

// 更新计划状态
function updatePlanStatus(planMetadata, targetStep) {
  const activePlanPath = path.join(__dirname, '../memory/codegen-plan-active.md');
  
  if (!fs.existsSync(activePlanPath)) {
    console.log('⚠️ 没有活跃计划文件，跳过更新');
    return;
  }
  
  let content = fs.readFileSync(activePlanPath, 'utf-8');
  
  // 更新步骤状态
  const lines = content.split('\n');
  const newLines = lines.map(line => {
    const match = line.match(/- \[([ x])\] (\d+)\. (.+)/);
    if (match) {
      const stepNum = parseInt(match[2]);
      if (stepNum > targetStep) {
        return `- [ ] ${match[2]}. ${match[3]}`;
      }
    }
    return line;
  });
  
  content = newLines.join('\n');
  
  // 更新进度
  const completedSteps = planMetadata.steps.filter(s => s.step <= targetStep && s.status === 'completed');
  const totalSteps = planMetadata.steps.length;
  const percentage = Math.floor((completedSteps.length / totalSteps) * 100);
  
  content = content.replace(
    /\*\*完成\*\*:\s*\d+\/\d+\s*\(\d+%\)/,
    `**完成**: ${completedSteps.length}/${totalSteps} (${percentage}%)`
  );
  
  // 更新当前步骤
  const nextStep = planMetadata.steps.find(s => s.step === targetStep + 1);
  content = content.replace(
    /\*\*当前步骤\*\*:\s*.+/,
    `**当前步骤**: ${nextStep ? `${nextStep.step}. ${nextStep.name}` : '无'}`
  );
  
  fs.writeFileSync(activePlanPath, content, 'utf-8');
  console.log('✅ 计划状态已更新');
}

// 主函数
function main() {
  const config = parseArgs();
  
  console.log('========================================');
  console.log('计划回滚工具');
  console.log('========================================');
  console.log(`计划 ID: ${config.planId}`);
  console.log(`目标步骤: ${config.targetStep}`);
  console.log('========================================\n');
  
  if (!config.planId) {
    console.error('❌ 请指定计划 ID: --plan=plan-xxx');
    process.exit(1);
  }
  
  // 读取计划元数据
  const metadataPath = path.join(__dirname, '../memory/rollback', config.planId, 'plan-metadata.json');
  
  if (!fs.existsSync(metadataPath)) {
    console.error('❌ 找不到计划元数据文件');
    process.exit(1);
  }
  
  const planMetadata = JSON.parse(fs.readFileSync(metadataPath, 'utf-8'));
  
  console.log(`项目: ${planMetadata.project}`);
  console.log(`计划: ${planMetadata.name}`);
  console.log(`回滚方式: ${planMetadata.rollbackMethod}`);
  console.log('');
  
  // 获取项目路径（从记忆中读取）
  const memoryPath = path.join(__dirname, '../memory/codegen-memory.md');
  let projectPath = '';
  
  if (fs.existsSync(memoryPath)) {
    const memoryContent = fs.readFileSync(memoryPath, 'utf-8');
    const pathMatch = memoryContent.match(/\*\*项目路径\*\*:\s*(.+)/);
    if (pathMatch) {
      projectPath = pathMatch[1].trim();
    }
  }
  
  if (!projectPath) {
    console.error('❌ 找不到项目路径');
    process.exit(1);
  }
  
  // 执行回滚
  let success = false;
  
  if (planMetadata.rollbackMethod === 'git') {
    success = rollbackWithGit(projectPath, planMetadata, config.targetStep);
  } else {
    success = rollbackWithFiles(projectPath, planMetadata, config.targetStep);
  }
  
  if (success) {
    // 更新计划状态
    updatePlanStatus(planMetadata, config.targetStep);
    console.log('\n========================================');
    console.log('✅ 回滚完成');
    console.log('========================================');
  } else {
    console.log('\n========================================');
    console.log('❌ 回滚失败');
    console.log('========================================');
    process.exit(1);
  }
}

// 运行
main();