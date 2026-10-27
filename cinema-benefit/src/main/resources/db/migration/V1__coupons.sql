CREATE TABLE coupon_template (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(80) NOT NULL,
 discount DECIMAL(10,2) NOT NULL, minimum DECIMAL(10,2) NOT NULL,
 remaining INT NOT NULL, valid_days INT NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_coupon (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, template_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL, expires_at DATETIME(3) NOT NULL, order_id BIGINT NULL,
 UNIQUE KEY uk_coupon_owner (template_id,user_id), KEY idx_coupon_owner(user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE coupon_reservation (
 order_id BIGINT NOT NULL PRIMARY KEY, coupon_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 original_amount DECIMAL(10,2) NOT NULL,discount DECIMAL(10,2) NOT NULL,status VARCHAR(16) NOT NULL,
 xid VARCHAR(128) NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE consumed_order_event(event_id VARCHAR(64) NOT NULL PRIMARY KEY,payload_hash CHAR(64) NOT NULL,received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--
-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- for AT mode you must to init this sql for you business database. the seata server not need it.
CREATE TABLE IF NOT EXISTS `undo_log`
(
    `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
    `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    `rollback_info` LONGBLOB     NOT NULL COMMENT 'rollback info',
    `log_status`    INT(11)      NOT NULL COMMENT '0:normal status,1:defense status',
    `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';
ALTER TABLE `undo_log` ADD INDEX `ix_log_created` (`log_created`);
