/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.ai.extraction.NodeFixture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content-model hint against the real event-logging 3.0.0 schema: the two cases the first live run
 * climbed one rung at a time (design 02 §6.2).
 */
class TestContentModels {

    private static final ContentModels MODELS = new ContentModels(List.of(NodeFixture.eventLoggingSchema()));

    @Test
    void doorIsDescribedWhole() {
        final List<String> door = MODELS.describe("Door");
        assertThat(door).isNotEmpty();
        assertThat(door.get(0))
                .startsWith("Door (in ")
                .contains("Name, Description?, Location {")
                .contains("SingleEntry, RemoveAll")
                .contains("It is one alternative of ((Device, Client?, Server?) | Door); another may be simpler");
    }

    @Test
    void theParentOfDescriptionAndTypeIsAlert() {
        final List<String> parents = MODELS.describeParentsOf("Description", List.of("Type"));
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0)).startsWith("Alert contains, in order: Type [Vulnerability | IDS | Malware | "
                                              + "Network | Change | Error | Other], Severity?");
    }

    @Test
    void aRequiredEnumeratedChildCarriesItsValues() {
        // The rung the live target question climbed: an Alert with Type "Alarm", which the schema does not know.
        assertThat(MODELS.describe("Alert").get(0)).contains("Type [Vulnerability | IDS | Malware | Network | Change "
                                                             + "| Error | Other]");
        assertThat(MODELS.namespace()).contains("event-logging:3");
    }

    @Test
    void aSequenceInsideAChoiceStaysARun() {
        // EventSource's choice in 3.0.0: a Device with an optional Client and Server, or a Door — not four
        // alternatives, which is what flattening the sequence would have said.
        assertThat(MODELS.describe("EventSource").get(0)).contains("((Device, Client?, Server?) | Door)");
    }

    @Test
    void anInventedChildStillFindsItsParentByWhatWasExpected() {
        assertThat(MODELS.describeParentsOf("Message", List.of("Type")))
                .anyMatch(text -> text.startsWith("Alert contains, in order: Type"));
    }

    @Test
    void anUnknownElementIsNothing() {
        assertThat(MODELS.describe("NoSuchElement")).isEmpty();
    }
}
