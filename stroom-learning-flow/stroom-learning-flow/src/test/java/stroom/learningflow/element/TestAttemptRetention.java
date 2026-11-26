/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.learningflow.LearningFlowConfig;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Outputs;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.mock.MockSecurityContext;
import stroom.util.time.StroomDuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/// Tests how the attempt retention job handles deleted pipelines.
///
/// Pipeline deletions are normally heard as events, but the job also checks for learning kept for a pipeline
/// that no longer exists and marks it deleted, in case no node heard the deletion. Learning for a pipeline
/// deleted for longer than the attempt retention period is forgotten.
class TestAttemptRetention {

    private final Attempts attempts = Mockito.mock(Attempts.class);
    private final Outputs outputs = Mockito.mock(Outputs.class);
    private final KeptLearning kept = Mockito.mock(KeptLearning.class);
    private final PipelineDeletions deletions = Mockito.mock(PipelineDeletions.class);
    private final PipelineStore pipelineStore = Mockito.mock(PipelineStore.class);
    private final LearningFlowConfig config = Mockito.mock(LearningFlowConfig.class);
    private final AttemptRetention retention = new AttemptRetention(attempts, outputs, kept, deletions,
            () -> pipelineStore, () -> config, new MockSecurityContext());

    @BeforeEach
    void setUp() {
        Mockito.when(config.getAttemptRetention()).thenReturn(StroomDuration.of(Duration.ofDays(30)));
        Mockito.when(pipelineStore.list()).thenReturn(List.of(pipeline("still-here")));
        Mockito.when(kept.pipelines()).thenReturn(Set.of("still-here", "gone-unheard"));
        Mockito.when(kept.deletedBefore(Mockito.anyLong())).thenReturn(List.of());
    }

    @Test
    void aPipelineGoneWithoutBeingHeardIsMarked() {
        retention.exec();

        Mockito.verify(deletions).deleted("gone-unheard");
        Mockito.verify(deletions, Mockito.never()).deleted("still-here");
    }

    @Test
    void aListThatCameBackShortMarksNothingThatIsThere() {
        // The list of pipelines is only a first check. A pipeline missing from it is marked deleted only when
        // reading that pipeline on its own also finds nothing.
        Mockito.when(pipelineStore.list()).thenReturn(List.of());
        Mockito.when(pipelineStore.readDocument(pipeline("still-here")))
                .thenReturn(PipelineDoc.builder().uuid("still-here").name("still-here").build());

        retention.exec();

        Mockito.verify(deletions, Mockito.never()).deleted("still-here");
        Mockito.verify(deletions).deleted("gone-unheard");
    }

    @Test
    void aPipelineTheStoreCannotFindIsGone() {
        // The database-backed store reports a missing document by throwing, rather than by returning null.
        Mockito.when(pipelineStore.readDocument(pipeline("gone-unheard")))
                .thenThrow(new DocumentNotFoundException(pipeline("gone-unheard")));

        retention.exec();

        Mockito.verify(deletions).deleted("gone-unheard");
    }

    @Test
    void aPipelineThatCannotBeReadIsLeftAsItIs() {
        Mockito.when(pipelineStore.list()).thenReturn(List.of());
        Mockito.when(pipelineStore.readDocument(Mockito.any())).thenThrow(new IllegalStateException("busy"));

        retention.exec();

        Mockito.verify(deletions, Mockito.never()).deleted(Mockito.anyString());
    }

    @Test
    void aMarkedPipelineThatIsBackIsItsOwnAgainAndNotForgotten() {
        // A pipeline marked deleted has reappeared with the same UUID, e.g. by importing an export of it, and
        // no node heard the event.
        Mockito.when(pipelineStore.list()).thenReturn(List.of(pipeline("still-here"), pipeline("came-back")));
        Mockito.when(kept.deletedBefore(Long.MAX_VALUE)).thenReturn(List.of("came-back"));

        retention.exec();

        Mockito.verify(deletions).restored("came-back");
        Mockito.verify(deletions, Mockito.never()).deleted("came-back");
        Mockito.verify(kept, Mockito.never()).forget("came-back");
    }

    @Test
    void learningDeletedLongerThanAttemptsAreKeptIsForgotten() {
        Mockito.when(kept.deletedBefore(Mockito.anyLong())).thenReturn(List.of("long-gone", "also-gone"));
        // A failure to forget one pipeline's learning does not stop the rest; it is retried on the next run.
        Mockito.doThrow(new IllegalStateException("locked")).when(kept).forget("long-gone");

        retention.exec();

        Mockito.verify(kept).forget("long-gone");
        Mockito.verify(kept).forget("also-gone");
    }

    @Test
    void learningKeptForEverIsMarkedButNeverForgotten() {
        Mockito.when(config.getAttemptRetention()).thenReturn(StroomDuration.ZERO);

        retention.exec();

        Mockito.verify(deletions).deleted("gone-unheard");
        Mockito.verify(kept, Mockito.never()).forget(Mockito.anyString());
    }

    @Test
    void aStoreThatCannotBeReadMarksNothingAndPrunesStill() {
        Mockito.when(pipelineStore.list()).thenThrow(new IllegalStateException("down"));

        retention.exec();

        Mockito.verify(deletions, Mockito.never()).deleted(Mockito.anyString());
        Mockito.verify(attempts).prune(Mockito.anyLong());
    }

    private static DocRef pipeline(final String uuid) {
        return new DocRef(PipelineDoc.TYPE, uuid, uuid);
    }
}
