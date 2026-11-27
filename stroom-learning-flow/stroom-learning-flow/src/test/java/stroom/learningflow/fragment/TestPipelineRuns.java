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

package stroom.learningflow.fragment;

import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPipelineRuns {

    @Test
    void whatTheElementsLoggedIsGatheredOnceEach() {
        final LoggingErrorReceiver logging = new LoggingErrorReceiver();
        logging.log(Severity.ERROR, null, new ElementId("xsltFilter"), "Bad record", null, null);
        logging.log(Severity.ERROR, null, new ElementId("xsltFilter"), "Bad record", null, null);
        logging.log(Severity.WARNING, null, new ElementId("dsParser"), "Odd line", null, null);
        logging.log(Severity.ERROR, null, new ElementId("elsewhere"), "Not asked about", null, null);

        final List<StoredError> diagnostics = PipelineRuns.diagnostics(capture(),
                List.of("dsParser", "xsltFilter"), logging, "IllegalStateException: ignored");

        assertThat(diagnostics)
                .extracting(StoredError::getMessage)
                .containsExactly("Odd line", "Bad record");
    }

    @Test
    void aFailureNobodyRecordedIsReportedAgainstTheFirstElement() {
        final List<StoredError> diagnostics = PipelineRuns.diagnostics(capture(),
                List.of("dsParser", "xsltFilter"), new LoggingErrorReceiver(), "IllegalStateException: broke");

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.getFirst().getSeverity()).isEqualTo(Severity.FATAL_ERROR);
        assertThat(diagnostics.getFirst().getElementId().getId()).isEqualTo("dsParser");
        assertThat(diagnostics.getFirst().getMessage()).isEqualTo("IllegalStateException: broke");
    }

    @Test
    void nothingSaidAndNothingFailedIsNothing() {
        assertThat(PipelineRuns.diagnostics(capture(), List.of("xsltFilter"), new LoggingErrorReceiver(), null))
                .isEmpty();
    }

    private static HeadlessCapture capture() {
        return new HeadlessCapture(null, null);
    }
}
