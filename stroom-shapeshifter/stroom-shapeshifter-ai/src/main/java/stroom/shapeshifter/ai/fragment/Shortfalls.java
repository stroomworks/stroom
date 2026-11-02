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

import stroom.shapeshifter.ai.scoring.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/// A session's full pass over its held stream (design 04 §4, A64): the bar asked of every record, with
/// the run going on past the ones that fall short and the first few of them kept — the record as the
/// parser cut it, what the transform made of it, and the verdict — as the evidence the next question is
/// built from. Bounded, since a stream of a million short records says nothing a handful does not.
public final class Shortfalls implements RecordJudge {

    private final RecordJudge bar;
    private final int atMost;
    private final List<Shortfall> kept = new ArrayList<>();
    private int judged;
    private int fallenShort;

    /// @param bar    The bar, record by record.
    /// @param atMost How many short records to keep.
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

    /// How many fell short, kept or not.
    public int shortCount() {
        return fallenShort;
    }

    /// The records kept, in the order they fell short.
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
    /// @param index   Which record, from zero, in output order.
    /// @param parsed  The record as the parser cut it, as a document of its own; null where the runner
    ///                could not say.
    /// @param output  What the transform made of it, as a document of its own.
    /// @param verdict The verdict that failed it.
    public record Shortfall(int index, String parsed, String output, Verdict verdict) {

    }
}
