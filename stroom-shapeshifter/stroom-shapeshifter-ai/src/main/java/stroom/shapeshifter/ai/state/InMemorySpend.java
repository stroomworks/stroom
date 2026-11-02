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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Spend;

import java.util.HashMap;
import java.util.Map;

/// The spend of A44 in memory, for a harness with no database. One node's count is not the cluster's,
/// which is the whole of why the table exists.
public final class InMemorySpend implements Spend {

    private final Map<String, Spent> byDocument = new HashMap<>();

    @Override
    public synchronized Spent record(final String docUuid, final long tokens, final int calls,
                                     final long windowMs) {
        final long now = System.currentTimeMillis();
        final Spent current = byDocument.get(docUuid);
        final Spent counting = current == null || now - current.windowStartMs() >= windowMs
                ? new Spent(now, 0L, 0)
                : current;
        final Spent spent = new Spent(counting.windowStartMs(), counting.tokens() + tokens,
                counting.calls() + calls);
        byDocument.put(docUuid, spent);
        return spent;
    }

    @Override
    public synchronized Spent spent(final String docUuid, final long windowMs) {
        final Spent current = byDocument.get(docUuid);
        final long now = System.currentTimeMillis();
        return current == null || now - current.windowStartMs() >= windowMs
                ? new Spent(now, 0L, 0)
                : current;
    }
}
