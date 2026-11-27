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

package stroom.learningflow.stage;

/// Counts what each document has spent on the model, across the whole cluster. The count must be
/// cluster-wide because a budget divided by the number of nodes is not a real budget, and spend on one node
/// would be invisible to the others. There is one row per document, counting over a time window, updated
/// atomically by whichever node made the call.
///
/// This only counts; [SpendBreaker] reads the counts and decides whether new sessions may open.
public interface Spend {

    /// Adds what a session cost to the document's count for the current window. If the window has ended,
    /// the count starts again from this call.
    ///
    /// @param docUuid  The UUID of the document to charge.
    /// @param tokens   The tokens the model charged, if it reports them; zero if it does not.
    /// @param calls    How many questions were put to the model.
    /// @param windowMs How long a window lasts, so that the count reflects recent spending.
    /// @return What the document has spent in the window this call belongs to, including this call.
    Spent record(String docUuid, long tokens, int calls, long windowMs);

    /// Gets what the document has spent in its current window, without adding to it.
    Spent spent(String docUuid, long windowMs);

    /// Gets what **every** document has spent recently, added together, for comparison with the
    /// cluster-wide ceiling. A per-document ceiling cannot limit a cluster (a hundred documents, each within
    /// its own ceiling, can spend a hundred times it), so the breaker needs both.
    ///
    /// Windows are per document and do not line up, so this is the sum of each document's own current
    /// window rather than a single window across the cluster. That over-counts slightly at a window's
    /// edge, which is the safe direction for a safety limit to err in.
    ///
    /// @param windowMs How long a window lasts.
    /// @return The totals; `windowStartMs` is the earliest window still counting, or now where nothing
    ///         has been spent.
    Spent spentAll(long windowMs);


    // --------------------------------------------------------------------------------


    /// An amount spent within a window.
    ///
    /// @param windowStartMs When the window began.
    /// @param tokens        Tokens charged in the window.
    /// @param calls         Questions put to the model in the window.
    record Spent(long windowStartMs, long tokens, int calls) {

    }
}
