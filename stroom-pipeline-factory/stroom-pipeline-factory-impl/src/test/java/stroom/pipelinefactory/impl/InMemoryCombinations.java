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

package stroom.pipelinefactory.impl;

import stroom.pipelinefactory.impl.MetCombination.State;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// [Combinations] in memory, for the runner's tests.
class InMemoryCombinations implements Combinations {

    private final Map<String, Long> lastMetaIds = new LinkedHashMap<>();
    private final Map<String, Map<Combination, MetCombination>> met = new LinkedHashMap<>();
    private long now = 1_000L;

    @Override
    public long lastMetaId(final String factoryUuid) {
        return lastMetaIds.getOrDefault(factoryUuid, 0L);
    }

    @Override
    public void lastMetaId(final String factoryUuid, final long lastMetaId) {
        lastMetaIds.put(factoryUuid, lastMetaId);
    }

    @Override
    public boolean met(final String factoryUuid, final Combination combination) {
        final Map<Combination, MetCombination> rows = rows(factoryUuid);
        if (rows.containsKey(combination)) {
            return false;
        }
        rows.put(combination, new MetCombination(combination, State.PENDING, 0, null, null, null, now, now));
        return true;
    }

    @Override
    public void made(final String factoryUuid, final Combination combination, final Made made) {
        final MetCombination row = rows(factoryUuid).get(combination);
        rows(factoryUuid).put(combination, new MetCombination(combination, State.MADE, row.tries() + 1,
                made.pipeline(), made.filter().getUuid(), null, row.createTimeMs(), now));
    }

    @Override
    public void failed(final String factoryUuid, final Combination combination, final String message) {
        final MetCombination row = rows(factoryUuid).get(combination);
        rows(factoryUuid).put(combination, new MetCombination(combination, State.FAILED, row.tries() + 1,
                null, null, message, row.createTimeMs(), now));
    }

    @Override
    public List<Combination> unmade(final String factoryUuid, final long beforeMs) {
        return rows(factoryUuid).values().stream()
                .filter(row -> row.state() != State.MADE && row.updateTimeMs() < beforeMs)
                .map(MetCombination::combination)
                .toList();
    }

    @Override
    public List<MetCombination> list(final String factoryUuid) {
        return new ArrayList<>(rows(factoryUuid).values());
    }

    @Override
    public boolean forget(final String factoryUuid, final Combination combination) {
        return rows(factoryUuid).remove(combination) != null;
    }

    @Override
    public void forgetFactory(final String factoryUuid) {
        met.remove(factoryUuid);
        lastMetaIds.remove(factoryUuid);
    }

    /// @param now What the rows written from here on are stamped with.
    void at(final long now) {
        this.now = now;
    }


    private Map<Combination, MetCombination> rows(final String factoryUuid) {
        return met.computeIfAbsent(factoryUuid, uuid -> new LinkedHashMap<>());
    }
}
