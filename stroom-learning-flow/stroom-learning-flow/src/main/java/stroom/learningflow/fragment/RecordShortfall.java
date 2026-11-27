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

package stroom.learningflow.fragment;

import stroom.learningflow.scoring.Verdict;
import stroom.processor.api.DeferTaskException;

/// Thrown by [RecordGate] from inside a fragment's pipeline when a record falls short of the bar while a
/// stream is being served, to stop the pipeline reading any further. [PipelineFragmentRunner] catches it
/// and returns the record and its verdict to the stage. It extends [DeferTaskException] so that the
/// pipeline's layers let it through as they let any deferral through, and so that if it ever escaped, the
/// task would be deferred rather than failed.
public final class RecordShortfall extends DeferTaskException {

    private final transient int index;
    private final transient Verdict verdict;
    private final transient String record;

    /// @param index   The record's zero-based index, in output order.
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
