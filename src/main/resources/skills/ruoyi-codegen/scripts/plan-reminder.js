#!/usr/bin/env node

/**
 * 计划进度提醒脚本
 * 用于定时提醒用户当前计划执行进度
 * 
 * 使用方式：
 * node plan-reminder.js
 * 
 * 功能：
 * 1. 读取 codegen-plan-active.md
 * 2. 解析当前进度
 * 3. 发送提醒消息到飞书
 */

const fs = require('fs');
const path = require('path');

// 文件路径
const PLAN_FILE = path.join(__dirname, '../memory/codegen-plan-active.md');
const MEMORY_FILE = path.join(__dirname, '../memory/codegen-memory.md');

// 解析计划文件
function parsePlanFile() {
  try {
    const content = fs.readFileSync(PLAN_FILE, 'utf-8');
    
    // 检查是否有活跃计划
    if (content.includes('（待填写）') || content.includes('（无活跃计划）')) {
      return null;
    }
    
    // 解析基本信息
    const projectMatch = content.match(/\*\*项目\*\*:\s*(.+)/);
    const nameMatch = content.match(/\*\*计划名称\*\*:\s*(.+)/);
    const statusMatch = content.match(/\*\*状态\*\*:\s*(.+)/);
    const progressMatch = content.match(/\*\*完成\*\*:\s*(\d+)\/(\d+)\s*\((\d+)%\)/);
    const currentStepMatch = content.match(/\*\*当前步骤\*\*:\s*(.+)/);
    
    // 解析步骤列表
    const steps = [];
    const stepRegex = /- \[([ x])\] (\d+)\. (.+)/g;
    let match;
    while ((match = stepRegex.exec(content)) !== null) {
      steps.push({
        completed: match[1] === 'x',
        number: parseInt(match[2]),
        name: match[3]
      });
    }
    
    return {
      project: projectMatch ? projectMatch[1].trim() : '未知',
      name: nameMatch ? nameMatch[1].trim() : '未知',
      status: statusMatch ? statusMatch[1].trim() : '未知',
      completed: progressMatch ? parseInt(progressMatch[1]) : 0,
      total: progressMatch ? parseInt(progressMatch[2]) : 0,
      percentage: progressMatch ? parseInt(progressMatch[3]) : 0,
      currentStep: currentStepMatch ? currentStepMatch[1].trim() : '无',
      steps: steps
    };
  } catch (error) {
    console.error('解析计划文件失败:', error.message);
    return null;
  }
}

// 生成进度条
function generateProgressBar(percentage) {
  const filled = Math.floor(percentage / 10);
  const empty = 10 - filled;
  return '▓'.repeat(filled) + '░'.repeat(empty);
}

// 生成提醒消息
function generateReminderMessage(plan) {
  if (!plan) {
    return null;
  }
  
  // 找到当前步骤和下一步
  const currentStepIndex = plan.steps.findIndex(s => !s.completed);
  const currentStep = currentStepIndex >= 0 ? plan.steps[currentStepIndex] : null;
  const nextStep = currentStepIndex >= 0 && currentStepIndex < plan.steps.length - 1 
    ? plan.steps[currentStepIndex + 1] 
    : null;
  
  // 找到已完成的步骤
  const completedSteps = plan.steps.filter(s => s.completed);
  
  let message = `⏰ 计划执行提醒\n\n`;
  message += `📋 项目：${plan.project}\n`;
  message += `📌 计划：${plan.name}\n`;
  message += `📊 进度：${plan.completed}/${plan.total} (${plan.percentage}%) ${generateProgressBar(plan.percentage)}\n\n`;
  
  if (completedSteps.length > 0) {
    message += `✅ 已完成：\n`;
    completedSteps.slice(-3).forEach(step => {
      message += `  ${step.number}. ${step.name} ✅\n`;
    });
    message += `\n`;
  }
  
  if (currentStep) {
    message += `🔄 当前步骤：\n`;
    message += `  ${currentStep.number}. ${currentStep.name} ⬅️ 执行中...\n\n`;
  }
  
  if (nextStep) {
    message += `⏭️ 下一步：\n`;
    message += `  ${nextStep.number}. ${nextStep.name}\n\n`;
  }
  
  // 预计剩余时间（假设每步平均 3 分钟）
  const remainingSteps = plan.total - plan.completed;
  const estimatedMinutes = remainingSteps * 3;
  message += `⏱️ 预计剩余时间：约 ${estimatedMinutes} 分钟\n`;
  
  return message;
}

// 主函数
function main() {
  console.log('========================================');
  console.log('计划进度提醒');
  console.log('========================================');
  console.log(`时间: ${new Date().toLocaleString('zh-CN')}`);
  console.log('========================================\n');
  
  // 解析计划文件
  const plan = parsePlanFile();
  
  if (!plan) {
    console.log('❌ 没有活跃计划');
    return;
  }
  
  // 生成提醒消息
  const message = generateReminderMessage(plan);
  
  if (message) {
    console.log(message);
    console.log('========================================');
    console.log('✅ 提醒消息已生成');
    console.log('========================================');
  }
}

// 运行
main();