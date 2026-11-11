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

package stroom.dependency.client.layout;

import stroom.test.common.TestUtil;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDetailLevel {

    @TestFactory
    Stream<DynamicTest> testForZoom() {
        return TestUtil.buildDynamicTestStream()
                .withInputType(Double.class)
                .withOutputType(DetailLevel.class)
                .withSingleArgTestFunction(DetailLevel::forZoom)
                .withSimpleEqualityAssertion()
                .addCase(0.08, DetailLevel.LANES_COLLAPSED)
                .addCase(0.41, DetailLevel.LANES_COLLAPSED)
                .addCase(0.42, DetailLevel.REFERENCES_COLLAPSED)
                .addCase(0.61, DetailLevel.REFERENCES_COLLAPSED)
                .addCase(0.62, DetailLevel.EVERYTHING)
                .addCase(1.0, DetailLevel.EVERYTHING)
                .addCase(2.4, DetailLevel.EVERYTHING)
                .build();
    }

    @Test
    void testFromLevel() {
        for (final DetailLevel detailLevel : DetailLevel.values()) {
            assertThat(DetailLevel.fromLevel(detailLevel.getLevel()))
                    .isEqualTo(detailLevel);
        }
        assertThatThrownBy(() -> DetailLevel.fromLevel(3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
