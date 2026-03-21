-- 菜单权限表
CREATE TABLE `system_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `name` varchar(50) NOT NULL COMMENT '菜单名称',
  `permission` varchar(100) NOT NULL DEFAULT '' COMMENT '权限标识',
  `type` tinyint NOT NULL COMMENT '菜单类型',
  `sort` int NOT NULL DEFAULT '0' COMMENT '显示顺序',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父菜单ID',
  `path` varchar(200) DEFAULT '' COMMENT '路由地址',
  `icon` varchar(100) DEFAULT '#' COMMENT '菜单图标',
  `component` varchar(255) COMMENT '组件路径',
  `component_name` varchar(255) COMMENT '组件名',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '菜单状态',
  `visible` bit(1) NOT NULL DEFAULT 'b'1'' COMMENT '是否可见',
  `keep_alive` bit(1) NOT NULL DEFAULT 'b'1'' COMMENT '是否缓存',
  `always_show` bit(1) NOT NULL DEFAULT 'b'1'' COMMENT '是否总是显示',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT 'CURRENT_TIMESTAMP' COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT 'CURRENT_TIMESTAMP' COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT 'b'0'' COMMENT '是否删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限表';
