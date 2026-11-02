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

package stroom.shapeshifter.shared;

import stroom.docref.HasDisplayValue;

/// What a variant can be run again over (ruling A1, design 01 §4): the smallest thing the loop can
/// replay, which is decided by the fragment and not by anyone's preference.
///
/// A pipeline element is pushed its input once, so running a candidate twice means having the input
/// again. Where a parser stands at the head of the chain there is nothing smaller than the stream to
/// have — the raw bytes above it cannot be replayed from a record — and where the chain begins with a
/// transform the records already exist and one of them is enough.
///
/// It is derived from the fragment at build time rather than declared on the document (A1 revised,
/// 2026-09-17), because under A21 the model chooses the chain per learner and a document that spans
/// several learners may span both kinds. It is not what decides *scoring* granularity, which follows the
/// chain: stream-level after a parser, per record after a filter.
public enum ReplayUnit implements HasDisplayValue {

    /// The chain contains a parser, so what is replayed is the stream — or a bounded prefix of it,
    /// re-read from the store, which is re-readable by construction.
    STREAM("Stream"),

    /// The chain begins with a transform, so the records exist already and one of them is the unit: the
    /// record's events are buffered and replayed, which costs one record's work.
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
