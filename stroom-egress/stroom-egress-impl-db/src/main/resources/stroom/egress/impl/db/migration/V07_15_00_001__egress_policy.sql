-- ------------------------------------------------------------------------
-- Copyright 2026 Crown Copyright
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
-- ------------------------------------------------------------------------

-- Stop NOTE level warnings about objects (not)? existing
SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0;

--
-- The egress allow-list: one row per rule.
--
CREATE TABLE IF NOT EXISTS egress_rule (
    id              bigint(20) NOT NULL AUTO_INCREMENT,
    kind            varchar(32) NOT NULL,
    destination     varchar(2048) NOT NULL,
    match_type      varchar(16) NOT NULL,
    comment         varchar(1024) DEFAULT NULL,
    enabled         tinyint(1) NOT NULL DEFAULT 1,
    create_time_ms  bigint(20) NOT NULL,
    create_user     varchar(255) NOT NULL,
    update_time_ms  bigint(20) NOT NULL,
    update_user     varchar(255) NOT NULL,
    PRIMARY KEY (id),
    KEY egress_rule_kind (kind)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- The mode and kill switch for each kind of destination. A kind with no row
-- is in report-only mode with its kill switch off.
--
CREATE TABLE IF NOT EXISTS egress_setting (
    kind            varchar(32) NOT NULL,
    mode            varchar(16) NOT NULL,
    kill_switch     tinyint(1) NOT NULL DEFAULT 0,
    update_time_ms  bigint(20) NOT NULL,
    update_user     varchar(255) NOT NULL,
    PRIMARY KEY (kind)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
