-- ------------------------------------------------------------------------
-- Copyright 2025 Crown Copyright
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
-- Create the Visualisation Assets table.
-- Note max key length in MySQL InnoDB is 3072 bytes or 768 utf8 characters.
-- Thus the path is limited to 512 characters.
--
CREATE TABLE IF NOT EXISTS visualisation_assets (
  modified              bigint NOT NULL,
  owner_doc_uuid        varchar(255) NOT NULL,
  asset_uuid            varchar(255) NOT NULL,
  path                  varchar(512) NOT NULL,
  path_hash             binary(32) NOT NULL,
  is_folder             bool NOT NULL,
  data                  longblob NULL,
  CONSTRAINT pk_visualisation_assets PRIMARY KEY (owner_doc_uuid, path_hash),
  CONSTRAINT k_asset_uuid UNIQUE KEY (asset_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS visualisation_assets_draft (
  draft_user_uuid       varchar(255) NOT NULL,
  owner_doc_uuid        varchar(255) NOT NULL,
  asset_uuid            varchar(255) NOT NULL,
  path                  varchar(512) NOT NULL,
  path_hash             binary(32) NOT NULL,
  is_folder             bool NOT NULL,
  data                  longblob NULL,
  CONSTRAINT pk_visualisation_assets_draft PRIMARY KEY (draft_user_uuid, owner_doc_uuid, path_hash),
  CONSTRAINT k_asset_uuid UNIQUE KEY (asset_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;


-- vim: set shiftwidth=4 tabstop=4 expandtab:
