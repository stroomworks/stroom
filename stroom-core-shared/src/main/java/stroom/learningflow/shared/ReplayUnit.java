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

package stroom.learningflow.shared;

import stroom.docref.HasDisplayValue;

/// The smallest unit of input a candidate chain can be run again over, which depends on the elements in the
/// chain.
///
/// A pipeline element is given its input once, so running a candidate again means having the input again.
/// Where any element in the chain is a parser, nothing smaller than the stream will do, because the raw bytes
/// a parser reads cannot be recovered from a record. Where the chain has no parser, the records already exist
/// and one of them is enough.
///
/// It is worked out from the elements rather than set on the document, because the model chooses the chain
/// for each learner. The Smart Pipeline element sits where a parser sits, so it uses only the stream unit,
/// and it checks that a document's allowed elements agree with that.
public enum ReplayUnit implements HasDisplayValue {

    /// The chain contains a parser, so what is replayed is the stream, or a bounded prefix of it, read again
    /// from the stream store.
    STREAM("Stream"),

    /// The chain has no parser, so the records exist already and one record is the unit. The record's events
    /// are buffered and replayed, which costs one record's work.
    RECORD("Record");

    private final String displayValue;

    ReplayUnit(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
