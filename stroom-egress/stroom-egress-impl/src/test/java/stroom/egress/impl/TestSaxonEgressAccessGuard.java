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

package stroom.egress.impl;

import stroom.egress.api.EgressRule;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;
import stroom.util.xml.saxon.SaxonRoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestSaxonEgressAccessGuard {

    private InMemoryEgressRegistry registry;
    private AggregatingDecisionRecorder recorder;
    private SaxonEgressAccessGuard saxonGuard;

    @BeforeEach
    void setUp() {
        registry = new InMemoryEgressRegistry();
        recorder = new AggregatingDecisionRecorder();
        saxonGuard = new SaxonEgressAccessGuard(new EgressGuardImpl(registry, registry, recorder));
        registry.addRule(new EgressRule(
                1L, EgressKind.HTTP, "https://lookup.example.org/data/", EgressMatch.PREFIX, "lookups", true));
        registry.addRule(new EgressRule(
                2L, EgressKind.FILE, "/stroom/lookups", EgressMatch.PREFIX, "files", true));
    }

    @Test
    void testDocumentOverHttpFollowsTheHttpRule() {
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        assertThat(saxonGuard.permit(SaxonRoute.DOCUMENT, "https://lookup.example.org/data/a.xml")).isTrue();
        assertThat(saxonGuard.permit(SaxonRoute.DOCUMENT, "https://other.example.org/a.xml")).isFalse();
    }

    @Test
    void testDocumentOverFileFollowsTheFileRule() {
        registry.setMode(EgressKind.FILE, EgressMode.ENFORCE);
        assertThat(saxonGuard.permit(SaxonRoute.DOCUMENT, "file:///stroom/lookups/a.xml")).isTrue();
        assertThat(saxonGuard.permit(SaxonRoute.DOCUMENT, "file:///etc/passwd")).isFalse();
    }

    @Test
    void testDocumentFollowsTheModeSoReportOnlyProceedsAndRecords() {
        assertThat(saxonGuard.permit(SaxonRoute.DOCUMENT, "https://other.example.org/a.xml")).isTrue();
        assertThat(recorder.getAttempts()).hasSize(1);
    }

    @Test
    void testDeniedOutrightRoutesAreDeniedInEveryModeEvenWithARule() {
        for (final EgressMode mode : EgressMode.values()) {
            registry.setMode(EgressKind.FILE, mode);
            registry.setMode(EgressKind.HTTP, mode);
            assertThat(saxonGuard.permit(SaxonRoute.UNPARSED_TEXT, "file:///stroom/lookups/a.txt")).isFalse();
            assertThat(saxonGuard.permit(SaxonRoute.COLLECTION, "file:///stroom/lookups/")).isFalse();
            assertThat(saxonGuard.permit(SaxonRoute.RESULT_DOCUMENT, "file:///stroom/lookups/o.xml")).isFalse();
            assertThat(saxonGuard.permit(SaxonRoute.UNPARSED_TEXT, "https://lookup.example.org/data/a.txt"))
                    .isFalse();
        }
        assertThat(recorder.getAttempts()).isNotEmpty();
    }

    @Test
    void testUnknownSchemeIsNotPermittedInEnforce() {
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        assertThat(saxonGuard.permit(SaxonRoute.DOCUMENT, "jar:file:///x.jar!/a.xml")).isFalse();
    }
}
