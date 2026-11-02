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

package stroom.shapeshifter.ai.stage;

/// What a document has spent on the model, counted across the cluster (A44): a budget divided by node
/// count is not a budget, and a feed burning spend on one node is invisible to the others. One row per
/// document, a token bucket over a window, updated atomically by whichever node made the call.
///
/// The counter is here; the policy that reads it — the per-document rate limit and the spend breaker that
/// opens error mode — is A24's, and is built with it in phase E.
public interface Spend {

    /// Count what an attempt cost, against the document's current window. Where the window has run out the
    /// count starts again from this call.
    ///
    /// @param tokens What the model charged, where it says; zero where it does not.
    /// @param calls  How many questions were put to it.
    /// @param windowMs How long a window lasts, so that a count is of what was spent lately.
    /// @return What the document has spent in the window this call belongs to, including this call.
    Spent record(String docUuid, long tokens, int calls, long windowMs);

    /// What the document has spent in the window it is in, without adding to it.
    Spent spent(String docUuid, long windowMs);


    // --------------------------------------------------------------------------------


    /// @param windowStartMs When the window this counts began.
    /// @param tokens        Tokens charged in it.
    /// @param calls         Questions put to the model in it.
    record Spent(long windowStartMs, long tokens, int calls) {

    }
}
