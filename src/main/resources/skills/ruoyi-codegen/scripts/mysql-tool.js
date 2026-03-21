#!/usr/bin/env node

/**
 * MySQL 数据库工具脚本
 * 用于连接 MySQL 数据库，查询表结构，生成/修改 DDL
 * 
 * 使用方式：
 * node mysql-tool.js --host=localhost --port=3306 --user=root --password=123456 --database=asset_disposal
 * 
 * 功能：
 * 1. 查询表结构
 * 2. 生成 DDL
 * 3. 修改 DDL
 * 4. 执行 DDL
 */

const mysql = require('mysql2/promise');
const fs = require('fs');
const path = require('path');

// 命令行参数解析
function parseArgs() {
  const args = process.argv.slice(2);
  const config = {
    host: 'localhost',
    port: 3306,
    user: 'root',
    password: '',
    database: '',
    action: 'query', // query | generate | alter | execute
    table: '',
    sql: ''
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--')) {
      const key = arg.substring(2);
      const value = args[i + 1];
      
      switch (key) {
        case 'host':
          config.host = value;
          i++;
          break;
        case 'port':
          config.port = parseInt(value);
          i++;
          break;
        case 'user':
          config.user = value;
          i++;
          break;
        case 'password':
          config.password = value;
          i++;
          break;
        case 'database':
          config.database = value;
          i++;
          break;
        case 'action':
          config.action = value;
          i++;
          break;
        case 'table':
          config.table = value;
          i++;
          break;
        case 'sql':
          config.sql = value;
          i++;
          break;
      }
    }
  }

  return config;
}

// 创建数据库连接
async function createConnection(config) {
  try {
    const connection = await mysql.createConnection({
      host: config.host,
      port: config.port,
      user: config.user,
      password: config.password,
      database: config.database
    });
    console.log('✅ 数据库连接成功');
    return connection;
  } catch (error) {
    console.error('❌ 数据库连接失败:', error.message);
    process.exit(1);
  }
}

// 查询表结构
async function queryTableStructure(connection, tableName) {
  try {
    // 查询表字段信息
    const [columns] = await connection.execute(`
      SELECT 
        COLUMN_NAME as '字段名',
        COLUMN_TYPE as '类型',
        IS_NULLABLE as '允许空',
        COLUMN_KEY as '键',
        COLUMN_DEFAULT as '默认值',
        EXTRA as '额外信息',
        COLUMN_COMMENT as '注释'
      FROM INFORMATION_SCHEMA.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
      ORDER BY ORDINAL_POSITION
    `, [tableName]);

    // 查询表索引信息
    const [indexes] = await connection.execute(`
      SELECT 
        INDEX_NAME as '索引名',
        COLUMN_NAME as '列名',
        NON_UNIQUE as '非唯一',
        INDEX_TYPE as '索引类型'
      FROM INFORMATION_SCHEMA.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
      ORDER BY INDEX_NAME, SEQ_IN_INDEX
    `, [tableName]);

    // 查询表注释
    const [tableInfo] = await connection.execute(`
      SELECT TABLE_COMMENT as '表注释'
      FROM INFORMATION_SCHEMA.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
    `, [tableName]);

    return {
      tableName,
      tableComment: tableInfo[0]?.['表注释'] || '',
      columns,
      indexes
    };
  } catch (error) {
    console.error('❌ 查询表结构失败:', error.message);
    return null;
  }
}

// 生成 DDL
function generateDDL(tableStructure) {
  const { tableName, tableComment, columns, indexes } = tableStructure;
  
  let ddl = `-- ${tableComment}\n`;
  ddl += `CREATE TABLE \`${tableName}\` (\n`;
  
  // 字段定义
  const columnDefs = columns.map(col => {
    let def = `  \`${col['字段名']}\` ${col['类型']}`;
    
    if (col['允许空'] === 'NO') {
      def += ' NOT NULL';
    }
    
    if (col['额外信息']?.includes('auto_increment')) {
      def += ' AUTO_INCREMENT';
    } else if (col['默认值'] !== null) {
      def += ` DEFAULT '${col['默认值']}'`;
    }
    
    if (col['注释']) {
      def += ` COMMENT '${col['注释']}'`;
    }
    
    return def;
  });
  
  ddl += columnDefs.join(',\n');
  
  // 主键
  const pkColumns = columns.filter(col => col['键'] === 'PRI');
  if (pkColumns.length > 0) {
    ddl += `,\n  PRIMARY KEY (\`${pkColumns.map(col => col['字段名']).join('`, `')}\`)`;
  }
  
  // 唯一索引
  const uniqueIndexes = {};
  indexes.forEach(idx => {
    if (idx['非唯一'] === 0 && idx['索引名'] !== 'PRIMARY') {
      if (!uniqueIndexes[idx['索引名']]) {
        uniqueIndexes[idx['索引名']] = [];
      }
      uniqueIndexes[idx['索引名']].push(idx['列名']);
    }
  });
  
  Object.keys(uniqueIndexes).forEach(indexName => {
    ddl += `,\n  UNIQUE KEY \`${indexName}\` (\`${uniqueIndexes[indexName].join('`, `')}\`)`;
  });
  
  // 普通索引
  const normalIndexes = {};
  indexes.forEach(idx => {
    if (idx['非唯一'] === 1) {
      if (!normalIndexes[idx['索引名']]) {
        normalIndexes[idx['索引名']] = [];
      }
      normalIndexes[idx['索引名']].push(idx['列名']);
    }
  });
  
  Object.keys(normalIndexes).forEach(indexName => {
    ddl += `,\n  KEY \`${indexName}\` (\`${normalIndexes[indexName].join('`, `')}\`)`;
  });
  
  ddl += `\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='${tableComment}';\n`;
  
  return ddl;
}

// 生成 ALTER TABLE DDL
function generateAlterDDL(tableName, alterations) {
  let ddl = `-- 修改表 ${tableName}\n`;
  ddl += `ALTER TABLE \`${tableName}\`\n`;
  
  const alters = alterations.map((alt, index) => {
    let sql = '';
    switch (alt.type) {
      case 'ADD_COLUMN':
        sql = `  ADD COLUMN \`${alt.column}\` ${alt.definition}`;
        break;
      case 'DROP_COLUMN':
        sql = `  DROP COLUMN \`${alt.column}\``;
        break;
      case 'MODIFY_COLUMN':
        sql = `  MODIFY COLUMN \`${alt.column}\` ${alt.definition}`;
        break;
      case 'ADD_INDEX':
        sql = `  ADD INDEX \`${alt.indexName}\` (\`${alt.columns.join('`, `')}\`)`;
        break;
      case 'DROP_INDEX':
        sql = `  DROP INDEX \`${alt.indexName}\``;
        break;
    }
    return sql + (index < alterations.length - 1 ? ',' : ';');
  });
  
  ddl += alters.join('\n');
  return ddl;
}

// 执行 SQL
async function executeSQL(connection, sql) {
  try {
    const [result] = await connection.execute(sql);
    console.log('✅ SQL 执行成功');
    console.log('影响行数:', result.affectedRows);
    return result;
  } catch (error) {
    console.error('❌ SQL 执行失败:', error.message);
    return null;
  }
}

// 格式化输出表结构
function printTableStructure(structure) {
  console.log('\n========================================');
  console.log(`表名: ${structure.tableName}`);
  console.log(`表注释: ${structure.tableComment}`);
  console.log('========================================\n');
  
  console.log('字段信息:');
  console.table(structure.columns);
  
  if (structure.indexes.length > 0) {
    console.log('\n索引信息:');
    console.table(structure.indexes);
  }
}

// 主函数
async function main() {
  const config = parseArgs();
  
  console.log('========================================');
  console.log('MySQL 数据库工具');
  console.log('========================================');
  console.log(`主机: ${config.host}:${config.port}`);
  console.log(`数据库: ${config.database}`);
  console.log(`操作: ${config.action}`);
  console.log('========================================\n');
  
  const connection = await createConnection(config);
  
  try {
    switch (config.action) {
      case 'query':
        // 查询表结构
        if (!config.table) {
          console.error('❌ 请指定表名: --table=表名');
          break;
        }
        const structure = await queryTableStructure(connection, config.table);
        if (structure) {
          printTableStructure(structure);
          
          // 生成 DDL
          const ddl = generateDDL(structure);
          console.log('\n生成的 DDL:');
          console.log('----------------------------------------');
          console.log(ddl);
          
          // 保存到文件
          const ddlPath = path.join(__dirname, `${config.table}_ddl.sql`);
          fs.writeFileSync(ddlPath, ddl, 'utf-8');
          console.log(`\n✅ DDL 已保存到: ${ddlPath}`);
        }
        break;
        
      case 'generate':
        // 仅生成 DDL（不执行）
        if (!config.table) {
          console.error('❌ 请指定表名: --table=表名');
          break;
        }
        const tableStruct = await queryTableStructure(connection, config.table);
        if (tableStruct) {
          const generatedDDL = generateDDL(tableStruct);
          console.log(generatedDDL);
        }
        break;
        
      case 'execute':
        // 执行 SQL
        if (!config.sql) {
          console.error('❌ 请指定 SQL: --sql="SQL语句"');
          break;
        }
        await executeSQL(connection, config.sql);
        break;
        
      case 'list':
        // 列出所有表
        const [tables] = await connection.execute(`
          SELECT TABLE_NAME as '表名', TABLE_COMMENT as '表注释', TABLE_ROWS as '行数'
          FROM INFORMATION_SCHEMA.TABLES
          WHERE TABLE_SCHEMA = DATABASE()
          ORDER BY TABLE_NAME
        `);
        console.log('\n数据库表列表:');
        console.table(tables);
        break;
        
      default:
        console.error('❌ 未知操作:', config.action);
        console.log('支持的操作: query | generate | execute | list');
    }
  } finally {
    await connection.end();
    console.log('\n✅ 数据库连接已关闭');
  }
}

// 运行
main().catch(console.error);