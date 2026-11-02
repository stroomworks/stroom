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

package stroom.pipelinefactory.shared;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.util.json.JsonUtil;
import stroom.util.shared.UserRef;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPipelineFactoryDoc {

    private static final DocRef TEMPLATE = PipelineDoc.buildDocRef().uuid("template-uuid").name("AI").build();
    private static final UserRef RUN_AS = UserRef.forUserUuid("run-as-uuid");

    @Test
    void aFullyPopulatedFactoryRoundTrips() {
        final PipelineFactoryDoc doc = populated();

        final String json = JsonUtil.writeValueAsString(doc);
        final PipelineFactoryDoc read = JsonUtil.readValue(json, PipelineFactoryDoc.class);

        assertThat(read).isEqualTo(doc);
        assertThat(read.getFilterSettings().getMaxTaskCreationDelay())
                .isEqualTo(SimpleDuration.builder().time(5).timeUnit(TimeUnit.MINUTES).build());
    }

    @Test
    void aNewFactoryIsDisabledWithAKeyOfFeedAndTypeAndHasNoProblems() {
        final PipelineFactoryDoc doc = PipelineFactoryDoc.builder().uuid("u").name("new").build();

        assertThat(doc.isEnabled()).isFalse();
        assertThat(doc.getKey()).containsExactly("Feed", "Type");
        assertThat(doc.getFolderPattern()).isEqualTo("Feeds/${feed}");
        assertThat(doc.getNamePattern()).isEqualTo("${feed} ${type}");
        assertThat(doc.getFilterSettings().isEnabled()).isTrue();
        assertThat(doc.problems()).isEmpty();
    }

    @Test
    void aFactoryWrittenWithNoSettingsReadsBackWithTheDefaults() {
        final PipelineFactoryDoc read = JsonUtil.readValue(
                "{\"type\":\"PipelineFactory\",\"uuid\":\"u\",\"name\":\"bare\"}", PipelineFactoryDoc.class);

        assertThat(read).isEqualTo(PipelineFactoryDoc.builder().uuid("u").name("bare").build());
    }

    @Test
    void aKeyMayNameOnlyFieldsThatSortStreamsIntoKinds() {
        // A119: an id, a time or a count can be queried by a filter, but a pipeline per value of one is a
        // pipeline per stream.
        final PipelineFactoryDoc doc = valid().key(List.of("Feed", "Type", "Id", "Create Time")).build();

        assertThat(doc.problems()).containsExactly(
                "'Id' cannot be a key field: a key may name only Feed, Type, Pipeline",
                "'Create Time' cannot be a key field: a key may name only Feed, Type, Pipeline");
    }

    @Test
    void aKeyIsNotEmptyAndNamesAFieldOnce() {
        assertThat(valid().key(List.of()).namePattern("x").folderPattern("").build().problems())
                .containsExactly("The key needs at least one field");
        assertThat(valid().key(List.of("Feed", "Type", "Feed")).build().problems())
                .containsExactly("'Feed' is in the key twice");
    }

    @Test
    void theTemplateIsAPipeline() {
        final DocRef notAPipeline = DocRef.builder().type("XSLT").uuid("x").build();

        assertThat(valid().template(notAPipeline).build().problems())
                .containsExactly("The template must be a pipeline, not a XSLT");
    }

    @Test
    void aPatternNamesOnlyTheKeysFields() {
        final PipelineFactoryDoc doc = valid()
                .key(List.of("Feed"))
                .namePattern("${feed} ${type}")
                .folderPattern("Feeds/${feed")
                .build();

        assertThat(doc.problems()).containsExactly(
                "The folder pattern 'Feeds/${feed' opens a ${ it does not close",
                "The name pattern names ${type}, which is not in the key");
    }

    @Test
    void aMadePipelineNeedsAName() {
        assertThat(valid().namePattern(" ").build().problems())
                .containsExactly("A made pipeline needs a name, so the name pattern cannot be empty");
    }

    @Test
    void aPropertyValueSaysWhereItGoesAndNamesOnlyTheKeysFields() {
        final PipelineFactoryDoc doc = valid()
                .properties(List.of(
                        new PipelineFactoryProperty("", "learnedName", "${feed}"),
                        new PipelineFactoryProperty("shapeshifterAi", " ", "${system}")))
                .build();

        assertThat(doc.problems()).containsExactly(
                "A property value needs the id of the element it is written to",
                "A property value needs the name of the property it is written to",
                "The value for shapeshifterAi. =${system} names ${system}, which is not in the key");
    }

    @Test
    void anIncompleteFactoryMaySaveWhileDisabledButNotOnceEnabled() {
        final PipelineFactoryDoc.Builder incomplete = PipelineFactoryDoc.builder().uuid("u").name("half");

        assertThat(incomplete.enabled(false).build().problems()).isEmpty();
        assertThat(incomplete.enabled(true).build().problems()).containsExactly(
                "An enabled factory needs a template to make pipelines from",
                "An enabled factory needs a run-as user to make pipelines as");
    }

    private static PipelineFactoryDoc.Builder valid() {
        return PipelineFactoryDoc.builder()
                .uuid("factory-uuid")
                .name("raw events")
                .enabled(true)
                .template(TEMPLATE)
                .runAsUser(RUN_AS);
    }

    private static PipelineFactoryDoc populated() {
        return valid()
                .version("v1")
                .description("One AI pipeline per raw feed")
                .streamFilter(ExpressionOperator.builder()
                        .addTerm("Type", Condition.EQUALS, "Raw Events")
                        .build())
                .key(List.of("Feed", "Type"))
                .folderPattern("AI/${feed}")
                .namePattern("${feed} - ${type}")
                .properties(List.of(new PipelineFactoryProperty("shapeshifterAi", "learnedFolder", "AI/${feed}")))
                .filterSettings(PipelineFactoryFilterSettings.builder()
                        .priority(20)
                        .autoPriority(true)
                        .maxProcessingTasks(4)
                        .profileName("ai-nodes")
                        .enabled(false)
                        .export(true)
                        .minMetaCreateTimeMs(1_000L)
                        .maxMetaCreateTimeMs(2_000L)
                        .maxTaskCreationDelay(SimpleDuration.builder().time(5).timeUnit(TimeUnit.MINUTES).build())
                        .build())
                .build();
    }
}
