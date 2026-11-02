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

import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestScorerSet {

    @Test
    void testReadNullIsEmpty() {
        final ScorerSet set = new ScorerSet();
        set.read(null);
        assertThat(set.write()).isEmpty();
    }

    @Test
    void testWriteIsACopy() {
        final ScorerSet set = new ScorerSet();
        set.read(List.of(scorer(ScorerType.COMPILE)));
        final List<ScorerSetting> written = set.write();
        written.clear();
        assertThat(set.write()).hasSize(1);
    }

    @Test
    void testFirstUnusedType() {
        final ScorerSet set = new ScorerSet();
        assertThat(set.firstUnusedType()).isEqualTo(ScorerType.COMPILE);
        set.read(List.of(scorer(ScorerType.COMPILE), scorer(ScorerType.YIELD)));
        assertThat(set.firstUnusedType()).isEqualTo(ScorerType.INPUT_COVERAGE);
    }

    @Test
    void testFirstUnusedTypeWhenFull() {
        final List<ScorerSetting> all = new ArrayList<>();
        for (final ScorerType type : ScorerType.values()) {
            all.add(scorer(type));
        }
        final ScorerSet set = new ScorerSet();
        set.read(all);
        assertThat(set.firstUnusedType()).isNull();
    }

    @Test
    void testFirstUnusedTypeOffersOnlyScorersThatExist() {
        final List<ScorerSetting> built = new ArrayList<>();
        for (final ScorerType type : ScorerType.values()) {
            if (type.isBuilt()) {
                built.add(scorer(type));
            }
        }
        final ScorerSet set = new ScorerSet();
        set.read(built);
        // Error load and event classification are missing from the set, but have no scorer to add
        assertThat(set.firstUnusedType()).isNull();
    }

    @Test
    void testDuplicate() {
        final ScorerSet set = new ScorerSet();
        set.read(List.of(scorer(ScorerType.COMPILE), scorer(ScorerType.YIELD)));
        // Adding one already there
        assertThat(set.duplicate(ScorerType.YIELD, null))
                .isEqualTo("The Yield scorer is already in the set.");
        // Adding one not there
        assertThat(set.duplicate(ScorerType.ERROR_LOAD, null)).isNull();
        // Editing one, keeping its type
        assertThat(set.duplicate(ScorerType.YIELD, ScorerType.YIELD)).isNull();
        // Editing one, taking another's type
        assertThat(set.duplicate(ScorerType.COMPILE, ScorerType.YIELD))
                .isEqualTo("The Compile scorer is already in the set.");
        assertThat(set.duplicate(null, null)).isNull();
    }

    @Test
    void testReplaceKeepsThePlace() {
        final ScorerSet set = new ScorerSet();
        set.read(List.of(scorer(ScorerType.COMPILE), scorer(ScorerType.YIELD), scorer(ScorerType.ERROR_LOAD)));
        final ScorerSetting replacement = scorer(ScorerType.BUSINESS_RULES);
        assertThat(set.replace(ScorerType.YIELD, replacement)).isTrue();
        assertThat(set.write())
                .extracting(ScorerSetting::getType)
                .containsExactly(ScorerType.COMPILE, ScorerType.BUSINESS_RULES, ScorerType.ERROR_LOAD);
    }

    @Test
    void testReplaceRemoved() {
        final ScorerSet set = new ScorerSet();
        set.read(List.of(scorer(ScorerType.COMPILE)));
        assertThat(set.replace(ScorerType.YIELD, scorer(ScorerType.YIELD))).isFalse();
        assertThat(set.write())
                .extracting(ScorerSetting::getType)
                .containsExactly(ScorerType.COMPILE);
    }

    @Test
    void testAddFindAndRemove() {
        final ScorerSet set = new ScorerSet();
        final ScorerSetting yield = scorer(ScorerType.YIELD);
        set.add(yield);
        assertThat(set.find(ScorerType.YIELD)).isSameAs(yield);
        assertThat(set.find(ScorerType.COMPILE)).isNull();
        set.remove(ScorerType.YIELD);
        assertThat(set.find(ScorerType.YIELD)).isNull();
        // Removing one not there changes nothing
        set.remove(ScorerType.COMPILE);
        assertThat(set.write()).isEmpty();
    }

    private static ScorerSetting scorer(final ScorerType type) {
        return new ScorerSetting(type, null, null, null, null);
    }
}
