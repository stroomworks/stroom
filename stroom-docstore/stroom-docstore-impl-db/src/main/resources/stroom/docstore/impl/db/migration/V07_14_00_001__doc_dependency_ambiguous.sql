-- ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
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
-- ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

-- Marks a dependency edge that was recorded from a name matching more than one
-- document. An XSLT refers to other documents by name, and names are not unique,
-- so an import or dictionary name can match several documents. Each match is
-- recorded as an edge, and this flag says the edge is one of several candidates
-- rather than a dependency the document will actually follow. At runtime an
-- ambiguous import fails to compile, and an ambiguous dictionary uses whichever
-- match is found first, so these edges are surfaced as problems to be fixed.

-- stop note level warnings about objects (not)? existing
SET @old_sql_notes=@@sql_notes, sql_notes=0;

ALTER TABLE doc_dependency ADD COLUMN ambiguous BOOLEAN NOT NULL DEFAULT FALSE;

SET SQL_NOTES=@OLD_SQL_NOTES;
