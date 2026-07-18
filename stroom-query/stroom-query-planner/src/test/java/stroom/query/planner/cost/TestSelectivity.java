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

package stroom.query.planner.cost;

import stroom.query.api.ExpressionTerm.Condition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3.2: the design doc's ordering, verified directly - equality &lt; range &lt; unindexed-scan.
 */
class TestSelectivity {

    @Test
    void equalityIsMoreSelectiveThanRange() {
        assertThat(Selectivity.forCondition(Condition.EQUALS))
                .isLessThan(Selectivity.forCondition(Condition.BETWEEN));
    }

    @Test
    void rangeIsMoreSelectiveThanUnindexed() {
        assertThat(Selectivity.forCondition(Condition.BETWEEN))
                .isLessThan(Selectivity.forCondition(Condition.NOT_EQUALS));
    }

    @Test
    void inConditionIsARangeTier() {
        assertThat(Selectivity.forCondition(Condition.IN)).isEqualTo(Selectivity.RANGE);
    }

    @Test
    void unrecognisedConditionDefaultsToUnindexed() {
        assertThat(Selectivity.forCondition(Condition.USER_HAS_PERM)).isEqualTo(Selectivity.UNINDEXED);
    }
}
