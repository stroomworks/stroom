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

package stroom.learningflow.transformation;

import stroom.learningflow.learning.StepRunner.Configured;
import stroom.pipeline.shared.XsltDoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestXsltStep {

    @Test
    void describesTheXsltFilter() {
        final XsltStep step = new XsltStep();

        assertThat(step.elementType()).isEqualTo("XSLTFilter");
        assertThat(step.elementId()).isEqualTo("xsltFilter");
        assertThat(step.configured()).contains(new Configured(XsltDoc.TYPE, "xslt"));
        assertThat(step.parser()).isFalse();
    }

    @Test
    void isNotRunItself() {
        // A node runs a stylesheet in a real pipeline built from this description, so nothing runs it
        // directly.
        assertThatThrownBy(() -> new XsltStep().run("<xsl:stylesheet/>", "<in/>"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new XsltStep().prepare("<xsl:stylesheet/>").run("<in/>"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theStandInDescribesTheSameElement() {
        final XsltStep described = new XsltStep();
        final StandInXsltStep standIn = new StandInXsltStep();

        assertThat(standIn.elementType()).isEqualTo(described.elementType());
        assertThat(standIn.elementId()).isEqualTo(described.elementId());
        assertThat(standIn.configured()).isEqualTo(described.configured());
    }
}
