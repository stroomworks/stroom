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

package stroom.shapeshifter.client.component;

import stroom.docref.DocRef;
import stroom.shapeshifter.client.component.Readiness.Fix;
import stroom.shapeshifter.client.component.Readiness.Item;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckPreset;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestReadiness {

    @Test
    void aNewDocumentIsNotReadyAndSaysHowToFixIt() {
        // What a new document has: learning off, no model, no checks, the direct flow
        final Readiness readiness = Readiness.of(ShapeshifterAiDoc.builder().uuid("new").name("New").build());
        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.getItems()).extracting(Item::getFix)
                .containsExactly(Fix.SWITCH_ON, Fix.CHOOSE_MODEL, null, null);
        assertThat(readiness.getItems().get(3).getText()).isEqualTo("Approach: Straight to it");
    }

    @Test
    void aDocumentWithEverythingIsReady() {
        final Readiness readiness = Readiness.of(ShapeshifterAiDoc.builder()
                .uuid("ss-1")
                .name("Firewall logs")
                .learningMode(LearningMode.AUTOMATIC)
                .model(new DocRef("OpenAIModel", "m-1", "GPT-4o"))
                .checks(CheckPreset.STANDARD.settings())
                .flow(LearningFlow.of(FlowExample.ESCALATING))
                .build());
        assertThat(readiness.isReady()).isTrue();
        assertThat(readiness.getItems()).extracting(Item::getText).containsExactly("Learning is on",
                "Model: GPT-4o", "6 quality gates", "Approach: Quick, then careful");
    }

    @Test
    void checksThatAreNoGateApartFromCompileSendAPersonToTheStandardSet() {
        final List<CheckSetting> noGates = CheckPreset.STANDARD.settings().stream()
                .map(setting -> setting.getCheck() == Check.COMPILE
                        ? setting
                        : setting.withState(CheckState.ADVICE))
                .toList();
        final Item checks = Readiness.of(ShapeshifterAiDoc.builder()
                .uuid("ss-1")
                .name("No gates")
                .checks(noGates)
                .build()).getItems().get(2);
        assertThat(checks.isOk()).isFalse();
        assertThat(checks.getFix()).isEqualTo(Fix.USE_STANDARD_CHECKS);
        assertThat(checks.getText()).isEqualTo("No quality check is a gate, so any answer that runs would pass.");
    }

    @Test
    void anApproachWithAProblemSendsAPersonToIt() {
        final Readiness readiness = Readiness.of(ShapeshifterAiDoc.builder()
                .uuid("ss-1")
                .name("Broken")
                .flow(new LearningFlow(List.of(FlowStep.of(QuestionKind.SPLIT)), null, null))
                .build());
        final Item approach = readiness.getItems().get(3);
        assertThat(approach.isOk()).isFalse();
        assertThat(approach.getFix()).isEqualTo(Fix.OPEN_APPROACH);
        assertThat(approach.getText()).startsWith("The approach has a problem: ");
    }
}
