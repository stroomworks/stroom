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


package stroom.pipelinefactory.client.component;

import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFactoryStreamFilter {

    private static final ExpressionOperator EMPTY = ExpressionOperator.builder().build();
    private static final ExpressionOperator WITH_TERM = ExpressionOperator.builder()
            .addTerm(ExpressionTerm.equals("Feed", "FEED_A"))
            .build();
    private static final ExpressionOperator OTHER_TERM = ExpressionOperator.builder()
            .addTerm(ExpressionTerm.equals("Type", "Events"))
            .build();

    @Test
    void testWrittenWithTermsIsKept() {
        assertThat(FactoryStreamFilter.toKeep(WITH_TERM, null)).isSameAs(WITH_TERM);
        assertThat(FactoryStreamFilter.toKeep(WITH_TERM, EMPTY)).isSameAs(WITH_TERM);
        assertThat(FactoryStreamFilter.toKeep(WITH_TERM, OTHER_TERM)).isSameAs(WITH_TERM);
    }

    @Test
    void testEmptyWrittenKeepsAnEmptyFilterTheDocumentHad() {
        assertThat(FactoryStreamFilter.toKeep(EMPTY, EMPTY)).isSameAs(EMPTY);
        assertThat(FactoryStreamFilter.toKeep(null, EMPTY)).isSameAs(EMPTY);
    }

    @Test
    void testEmptyWrittenKeepsNoFilterWhenTheDocumentHadNone() {
        assertThat(FactoryStreamFilter.toKeep(EMPTY, null)).isNull();
        assertThat(FactoryStreamFilter.toKeep(null, null)).isNull();
    }

    @Test
    void testEmptyWrittenClearsATermTheDocumentHad() {
        // The user removed every term: no filter, meaning every stream
        assertThat(FactoryStreamFilter.toKeep(EMPTY, WITH_TERM)).isNull();
    }
}
