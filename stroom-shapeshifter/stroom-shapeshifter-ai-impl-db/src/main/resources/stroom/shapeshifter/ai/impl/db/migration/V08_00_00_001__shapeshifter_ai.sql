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
-- The runtime state of Shapeshifter AI (A26), as one migration for Stroom 8.0 (A111). What is learned
-- belongs to a learner: a Shapeshifter AI element in a pipeline, named by the pipeline's UUID and the
-- element's id (design 06, A114). The document a learner runs under is recorded beside it, for the
-- Supervisor to show and to decide who may see a row, but is not part of its key.
--

--
-- A learner's learning state (A117, A124): given up, marked to learn again, the draft awaiting review,
-- and its rolling score. One row per learner, the pipeline and the element (A114); the document it runs
-- under is kept for the Supervisor, which lists what a person may see. The binding that serves it is the
-- learner's one binding that is not a draft.
--
CREATE TABLE IF NOT EXISTS shapeshifter_learner_state (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    pipeline_uuid         varchar(255) NOT NULL,
    element_id            varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    given_up_reason       longtext DEFAULT NULL,
    relearn_reason        longtext DEFAULT NULL,
    awaiting_binding_uuid varchar(255) DEFAULT NULL,
    rolling_score         double DEFAULT NULL,
    rolling_records       int NOT NULL DEFAULT 0,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_learner_state_learner (pipeline_uuid, element_id),
    KEY                   shapeshifter_learner_state_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What serves a learner (A124): its binding, and any draft awaiting review to take its place.
-- pipeline_uuid and element_id are the learner (A114); the fragment_* columns name the fragment the
-- binding runs. The document the learner runs under is kept for the Supervisor.
--
CREATE TABLE IF NOT EXISTS shapeshifter_binding (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    pipeline_uuid         varchar(255) NOT NULL,
    element_id            varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    binding_uuid          varchar(255) NOT NULL,
    fragment_type         varchar(255) DEFAULT NULL,
    fragment_uuid         varchar(255) DEFAULT NULL,
    fragment_name         varchar(255) DEFAULT NULL,
    draft                 tinyint NOT NULL DEFAULT 0,
    provisional           tinyint NOT NULL DEFAULT 0,
    promoted_time_ms      bigint DEFAULT NULL,
    score                 double DEFAULT NULL,
    boundary_element      varchar(255) DEFAULT NULL,
    boundary_array        varchar(255) DEFAULT NULL,
    boundary_depth        int DEFAULT NULL,
    snapshot_id           varchar(255) DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_binding_binding_uuid (binding_uuid),
    KEY                   shapeshifter_binding_learner (pipeline_uuid, element_id),
    KEY                   shapeshifter_binding_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What a document has spent on its model, counted across the cluster (A44). A budget is the document's
-- setting, so this stays the document's.
--
CREATE TABLE IF NOT EXISTS shapeshifter_spend (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    update_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    window_start_ms       bigint NOT NULL,
    tokens_spent          bigint NOT NULL DEFAULT 0,
    calls_made            int NOT NULL DEFAULT 0,
    breaker_open_since_ms bigint DEFAULT NULL,
    breaker_reason        longtext DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_spend_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- One learning session (A28). An open attempt is its learner's claim (A45): claimed is 1 while the
-- attempt is open and null once it closes, so one learner has at most one open attempt. The document is
-- the one the session ran under, and the feed and type those of the stream it opened on.
--
CREATE TABLE IF NOT EXISTS shapeshifter_attempt (
    id                    bigint NOT NULL AUTO_INCREMENT,
    version               int NOT NULL,
    create_time_ms        bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    pipeline_uuid         varchar(255) NOT NULL,
    element_id            varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    feed_name             varchar(255) DEFAULT NULL,
    type_name             varchar(255) DEFAULT NULL,
    input_meta_id         bigint DEFAULT NULL,
    node_name             varchar(255) DEFAULT NULL,
    promotion_mode        varchar(32) NOT NULL,
    status                varchar(32) NOT NULL,
    -- What it came to, in the words the decision itself uses, and the binding it wrote where it wrote one
    decision              longtext DEFAULT NULL,
    binding_uuid          varchar(255) DEFAULT NULL,
    score                 double DEFAULT NULL,
    tokens_spent          bigint NOT NULL DEFAULT 0,
    -- When a claim lapses if nothing heartbeats it (A45), for the worker to take up
    expiry_ms             bigint DEFAULT NULL,
    claimed               tinyint DEFAULT NULL,
    granted_tokens        bigint NOT NULL DEFAULT 0,
    granted_ms            bigint NOT NULL DEFAULT 0,
    exhaust_next          tinyint NOT NULL DEFAULT 0,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_attempt_claim (pipeline_uuid, element_id, claimed),
    KEY                   shapeshifter_attempt_doc_uuid (doc_uuid),
    KEY                   shapeshifter_attempt_status (status),
    KEY                   shapeshifter_attempt_create_time_ms (create_time_ms)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- One question of a session and its answer.
--
CREATE TABLE IF NOT EXISTS shapeshifter_turn (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    fk_attempt_id         bigint NOT NULL,
    turn_number           int NOT NULL,
    step_id               varchar(255) DEFAULT NULL,
    candidate             int NOT NULL DEFAULT 1,
    question_kind         varchar(32) NOT NULL,
    question              longtext NOT NULL,
    answer                longtext DEFAULT NULL,
    -- The answerer: the model a document names, or the user who answered instead; null while unanswered
    answered_by           varchar(255) DEFAULT NULL,
    outcome               varchar(32) DEFAULT NULL,
    -- The check that decided the turn and its score against its threshold (A144); null where no scorer did
    scorer                varchar(32) DEFAULT NULL,
    score                 double DEFAULT NULL,
    score_threshold       double DEFAULT NULL,
    -- The guidance the question carried (A45), so a re-walk replays what was used
    carried_guidance      text DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_turn_attempt_turn (fk_attempt_id, turn_number),
    CONSTRAINT            shapeshifter_turn_attempt_fk FOREIGN KEY (fk_attempt_id)
                              REFERENCES shapeshifter_attempt (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What a binding produced from each stream, so a stream can be reprocessed as it was processed (design 01
-- §7.3). One row per input per binding per pipeline (A124).
--
CREATE TABLE IF NOT EXISTS shapeshifter_output (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    binding_uuid          varchar(255) NOT NULL,
    input_meta_id         bigint NOT NULL,
    pipeline_uuid         varchar(255) NOT NULL DEFAULT '',
    fragment_uuid         varchar(255) NOT NULL,
    provisional           tinyint(1) NOT NULL DEFAULT 0,
    score                 double DEFAULT NULL,
    boundary_element      varchar(255) DEFAULT NULL,
    boundary_array        varchar(255) DEFAULT NULL,
    boundary_depth        int DEFAULT NULL,
    produce_time_ms       bigint NOT NULL DEFAULT 0,
    record_spans          mediumtext DEFAULT NULL,
    snapshot_id           varchar(255) DEFAULT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_output_binding_input_pipeline (binding_uuid, input_meta_id, pipeline_uuid),
    KEY                   shapeshifter_output_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- What a person has said about what a learner learns (A46), carried into every question it asks from
-- then on. The document is kept for who may see and withdraw it.
--
CREATE TABLE IF NOT EXISTS shapeshifter_guidance (
    id                    bigint NOT NULL AUTO_INCREMENT,
    create_time_ms        bigint NOT NULL,
    pipeline_uuid         varchar(255) NOT NULL,
    element_id            varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    message               longtext NOT NULL,
    -- Who said it. A hint is somebody's, and a person reading one a year later needs to know whose.
    author                varchar(255) NOT NULL,
    PRIMARY KEY           (id),
    KEY                   shapeshifter_guidance_learner (pipeline_uuid, element_id),
    KEY                   shapeshifter_guidance_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- A stream waiting for its learner (design 04 §3): one row per stream per learner. A promotion wakes the
-- learner's pipeline (A129), and the rows say which streams wait and on what. The document is
-- kept for the Supervisor's backlog, which lists what waits on the documents a person may see.
--
CREATE TABLE IF NOT EXISTS shapeshifter_deferral (
    id                    bigint NOT NULL AUTO_INCREMENT,
    pipeline_uuid         varchar(255) NOT NULL,
    element_id            varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    meta_id               bigint NOT NULL,
    feed_name             varchar(255) NOT NULL,
    type_name             varchar(255) NOT NULL,
    -- What the stream was run through and fell short of; null where there was nothing to run it through.
    failed_against        varchar(255) DEFAULT NULL,
    reason                longtext NOT NULL,
    failing_records       int NOT NULL,
    first_deferred_ms     bigint NOT NULL,
    update_time_ms        bigint NOT NULL,
    attempts              int NOT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_deferral_learner_meta (pipeline_uuid, element_id, meta_id),
    KEY                   shapeshifter_deferral_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- The records a binding has been held to (design 04 phase 3): what each became when it was accepted.
--
CREATE TABLE IF NOT EXISTS shapeshifter_corpus (
    id                    bigint NOT NULL AUTO_INCREMENT,
    doc_uuid              varchar(255) NOT NULL,
    binding_uuid          varchar(255) NOT NULL,
    meta_id               bigint NOT NULL,
    segment               int NOT NULL,
    -- TRANSLATION, UNKNOWN or NONE: what the record became when it was accepted.
    kind                  varchar(20) NOT NULL,
    score                 double NOT NULL,
    attempt_id            bigint DEFAULT NULL,
    accept_time_ms        bigint NOT NULL,
    PRIMARY KEY           (id),
    KEY                   shapeshifter_corpus_binding (binding_uuid, id),
    KEY                   shapeshifter_corpus_meta (meta_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- An immutable copy of a fragment's configuration as it was promoted (A61): what a binding points at and
-- what an as-processed reprocess runs.
--
CREATE TABLE IF NOT EXISTS shapeshifter_snapshot (
    id                    bigint NOT NULL AUTO_INCREMENT,
    snapshot_id           varchar(255) NOT NULL,
    doc_uuid              varchar(255) NOT NULL,
    binding_uuid          varchar(255) NOT NULL,
    fragment_uuid         varchar(255) NOT NULL,
    -- The fragment's PipelineData, as JSON.
    structure             longtext NOT NULL,
    -- Element id to the code that element ran with, as a JSON object.
    code                  longtext NOT NULL,
    -- Document uuid to the version of it the snapshot was taken from, as a JSON object: how a hand edit
    -- is noticed (A72).
    versions              longtext NOT NULL,
    create_time_ms        bigint NOT NULL,
    PRIMARY KEY           (id),
    UNIQUE KEY            shapeshifter_snapshot_id (snapshot_id),
    KEY                   shapeshifter_snapshot_binding (binding_uuid, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

--
-- A pipeline deleted while its learning is kept (A120, A134): its learners' rows stay, to be read, until
-- the retention job forgets them once the mark is older than attempts are kept. One row per pipeline,
-- since a pipeline's deletion is every one of its elements' at once.
--
CREATE TABLE IF NOT EXISTS shapeshifter_deleted_pipeline (
    pipeline_uuid         varchar(255) NOT NULL,
    deleted_time_ms       bigint NOT NULL,
    PRIMARY KEY           (pipeline_uuid),
    KEY                   shapeshifter_deleted_pipeline_deleted_time_ms (deleted_time_ms)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET SQL_NOTES=@OLD_SQL_NOTES;

-- vim: set shiftwidth=4 tabstop=4 expandtab:
