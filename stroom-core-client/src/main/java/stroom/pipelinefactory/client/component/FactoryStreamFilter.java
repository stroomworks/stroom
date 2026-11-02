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


package stroom.pipelinefactory.client.component;

import stroom.query.api.ExpressionOperator;

/// The filter on the streams a Pipeline Factory looks at, as its document keeps it.
public final class FactoryStreamFilter {

    private FactoryStreamFilter() {
        // Static methods only
    }

    /// An empty filter and no filter both mean every stream. Where the editor holds an empty one,
    /// the document keeps whichever of the two it had, so that opening a factory saved with an empty
    /// filter does not make it look changed.
    ///
    /// @param written The filter the editor holds, or null.
    /// @param had     The filter the document had, or null.
    /// @return The filter to keep: the one written if it has terms, else what the document had if it
    /// was empty, else null.
    public static ExpressionOperator toKeep(final ExpressionOperator written, final ExpressionOperator had) {
        if (written != null && written.hasChildren()) {
            return written;
        }
        return had == null || had.hasChildren()
                ? null
                : had;
    }
}
