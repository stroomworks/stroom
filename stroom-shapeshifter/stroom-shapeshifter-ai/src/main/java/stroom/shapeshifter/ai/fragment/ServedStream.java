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

import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;

/// What a serving run came to (design 04 §3): how many records it judged, how they scored, what the
/// fragment's elements said, and — where a record fell short — which, with its verdict. A run that fell
/// short has handed the downstream every record before that one and nothing after it; what the
/// downstream wrote of them is the task's to discard.
///
/// @param records     How many records were judged, the short one included.
/// @param meanTotal   The mean of the records' weighted totals, over the gates that judge a record: what
///                    the learner's rolling score is fed.
/// @param diagnostics What the fragment's elements said over the run, once each.
/// @param shortfall   The record that fell short, where one did.
public record ServedStream(int records,
                           double meanTotal,
                           List<StoredError> diagnostics,
                           Optional<RecordShortfall> shortfall) {

    public ServedStream {
        diagnostics = List.copyOf(diagnostics);
    }

    /// Whether every record judged cleared the bar.
    public boolean clear() {
        return shortfall.isEmpty();
    }
}
