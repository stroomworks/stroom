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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.CorpusIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// The corpus index as a list: for a node run without a database. Every method is synchronised.
public final class InMemoryCorpusIndex implements CorpusIndex {

    private final List<Row> rows = new ArrayList<>();

    @Override
    public synchronized List<Row> rows(final String bindingUuid) {
        return rows.stream().filter(row -> row.bindingUuid().equals(bindingUuid)).toList();
    }

    @Override
    public synchronized void add(final List<Row> added) {
        rows.addAll(added);
    }

    @Override
    public synchronized List<Long> trim(final String bindingUuid, final int cap) {
        final List<Row> kept = rows(bindingUuid);
        final List<Row> dropped = kept.size() > cap
                ? kept.subList(0, kept.size() - cap)
                : List.of();
        rows.removeAll(dropped);
        return orphaned(dropped);
    }

    @Override
    public synchronized List<Long> discard(final String bindingUuid) {
        final List<Row> dropped = rows(bindingUuid);
        rows.removeAll(dropped);
        return orphaned(dropped);
    }

    @Override
    public synchronized void move(final String fromBindingUuid, final String toBindingUuid) {
        final List<Row> moving = rows(fromBindingUuid);
        rows.removeAll(moving);
        moving.forEach(row -> rows.add(new Row(row.docUuid(), toBindingUuid, row.metaId(), row.segment(), row.kind(),
                row.score(), row.attemptId(), row.acceptTimeMs())));
    }

    private List<Long> orphaned(final List<Row> dropped) {
        final Set<Long> named = new HashSet<>();
        rows.forEach(row -> named.add(row.metaId()));
        return dropped.stream().map(Row::metaId).distinct().filter(id -> !named.contains(id)).toList();
    }
}
