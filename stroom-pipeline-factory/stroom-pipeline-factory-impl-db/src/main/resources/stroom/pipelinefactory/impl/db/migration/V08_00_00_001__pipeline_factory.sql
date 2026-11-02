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
-- What a Pipeline Factory has met (design 06 §3.4): one row per combination of its key's values, kept
-- whatever becomes of the pipeline made for it, so that a pipeline a person deletes is not made again.
-- A combination is its key's fields and values as JSON, fields in name order, and is unique per factory
-- through the SHA-256 of that text, since the text has no bound. The state is PENDING while the factory
-- is making it, MADE once the pipeline and filter exist, and FAILED with the reason when making them
-- did not work; anything not MADE is tried again on the factory's next run.
--
CREATE TABLE IF NOT EXISTS pipeline_factory_combination (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    factory_uuid          varchar(255) NOT NULL,
    combination_hash      char(64) NOT NULL,
    combination           longtext NOT NULL,
    state                 varchar(16) NOT NULL,
    tries                 int NOT NULL DEFAULT 0,
    pipeline_uuid         varchar(255) DEFAULT NULL,
    pipeline_name         varchar(255) DEFAULT NULL,
    filter_uuid           varchar(255) DEFAULT NULL,
    message               longtext DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            pipeline_factory_combination_factory_hash (factory_uuid, combination_hash),
    KEY                   pipeline_factory_combination_factory_state (factory_uuid, state)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- How far a Pipeline Factory has looked (design 06 §3.2): the highest stream id it has read, so that
-- each run reads only what has arrived since, as a processor filter's tracker does.
--
CREATE TABLE IF NOT EXISTS pipeline_factory_scan (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    update_time_ms        bigint NOT NULL,
    factory_uuid          varchar(255) NOT NULL,
    last_meta_id          bigint NOT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            pipeline_factory_scan_factory_uuid (factory_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
