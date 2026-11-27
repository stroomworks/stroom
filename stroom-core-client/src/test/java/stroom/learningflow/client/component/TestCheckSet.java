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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.CheckState;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests [CheckSet], which holds the Setup tab's checks while they are edited: one setting for every check,
/// edited in place.
class TestCheckSet {

    @Test
    void nothingReadIsEveryDefault() {
        final CheckSet set = new CheckSet();
        set.read(null);
        assertThat(set.write()).isEqualTo(CheckSettings.defaults());
    }

    @Test
    void aCheckNotNamedHasItsDefault() {
        final CheckSet set = new CheckSet();
        set.read(List.of(CheckSetting.of(Check.YIELD, CheckState.GATE)));
        assertThat(set.write()).hasSize(Check.values().length);
        assertThat(set.find(Check.YIELD).getState()).isEqualTo(CheckState.GATE);
        assertThat(set.find(Check.CONFORMANCE)).isEqualTo(CheckSettings.of(CheckSettings.defaults(),
                Check.CONFORMANCE));
    }

    @Test
    void theWrittenListIsACopy() {
        final CheckSet set = new CheckSet();
        set.read(null);
        set.write().clear();
        assertThat(set.write()).hasSize(Check.values().length);
    }

    @Test
    void aCheckIsReplacedWhereItWas() {
        final CheckSet set = new CheckSet();
        set.read(null);
        final CheckSetting advice = new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.7, null);
        set.replace(advice);
        assertThat(set.find(Check.COVERAGE)).isEqualTo(advice);
        assertThat(set.write().indexOf(advice)).isEqualTo(Check.COVERAGE.ordinal());
    }

    @Test
    void everyCheckIsShown() {
        final CheckSet set = new CheckSet();
        set.read(null);
        assertThat(set.shown()).extracting(CheckSetting::getCheck)
                .containsExactly(Check.values())
                .contains(Check.COMPILE, Check.WHOLENESS, Check.REGRESSION);
    }
}
