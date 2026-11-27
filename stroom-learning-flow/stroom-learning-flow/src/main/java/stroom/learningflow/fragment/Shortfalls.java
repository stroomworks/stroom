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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/// The [RecordJudge] for a learning session's full pass over its held stream. It applies the bar to every
/// record and lets the run carry on past records that fall short. The first few that fall short are kept
/// (the record as the parser produced it, what the transform made of it, and the verdict) as the evidence
/// the next question to the model is built from. The number kept is bounded, since a million failing
/// records say nothing that a handful do not.
public final class Shortfalls implements RecordJudge {

    private final RecordJudge bar;
    private final int atMost;
    private final List<Shortfall> kept = new ArrayList<>();
    private int judged;
    private int fallenShort;

    /// @param bar    The judge that applies the bar to each record.
    /// @param atMost The maximum number of failing records to keep.
    public Shortfalls(final RecordJudge bar, final int atMost) {
        this.bar = bar;
        this.atMost = atMost;
    }

    @Override
    public Verdict judge(final int index, final String record, final Supplier<Optional<String>> parsed) {
        final Verdict verdict = bar.judge(index, record, parsed);
        judged++;
        if (!RecordJudge.clears(verdict)) {
            fallenShort++;
            if (kept.size() < atMost) {
                kept.add(new Shortfall(index, parsed.get().orElse(null), record, verdict));
            }
        }
        return verdict;
    }

    @Override
    public boolean stops() {
        return false;
    }

    /// How many records were judged.
    public int judged() {
        return judged;
    }

    /// How many records fell short, whether kept or not.
    public int shortCount() {
        return fallenShort;
    }

    /// The failing records that were kept, in the order they fell short.
    public List<Shortfall> kept() {
        return List.copyOf(kept);
    }

    /// Whether every record cleared the bar.
    public boolean clear() {
        return fallenShort == 0;
    }

    // --------------------------------------------------------------------------------

    /// One record that fell short.
    ///
    /// @param index   The record's zero-based index, in output order.
    /// @param parsed  The record as the parser produced it, as a document of its own; null where the runner
    ///                could not provide it.
    /// @param output  What the transform made of it, as a document of its own.
    /// @param verdict The verdict that failed it.
    public record Shortfall(int index, String parsed, String output, Verdict verdict) {

    }
}
