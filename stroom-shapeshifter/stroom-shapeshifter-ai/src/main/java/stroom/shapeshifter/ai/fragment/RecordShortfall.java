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

package stroom.shapeshifter.ai.fragment;

import stroom.processor.api.DeferTaskException;
import stroom.shapeshifter.ai.scoring.Verdict;

/// A record fell short of the bar while the chain was being served (design 04 §3): thrown from inside the
/// fragment's pipeline to stop it reading any further, and caught by the runner, which hands the stage
/// the record and the verdict. It is a deferral by type so that the pipeline's layers let it through as
/// they let any deferral through, and so that one which somehow escaped would still mean *defer* and
/// never *fail*.
public final class RecordShortfall extends DeferTaskException {

    private final transient int index;
    private final transient Verdict verdict;
    private final transient String record;

    /// @param index   Which record, from zero, in output order.
    /// @param verdict The verdict that failed it.
    /// @param record  The record as the judge was given it.
    public RecordShortfall(final int index, final Verdict verdict, final String record) {
        super("Record " + (index + 1) + " fell short of the bar");
        this.index = index;
        this.verdict = verdict;
        this.record = record;
    }

    public int index() {
        return index;
    }

    public Verdict verdict() {
        return verdict;
    }

    public String record() {
        return record;
    }
}
