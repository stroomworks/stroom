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

import stroom.pipelinefactory.shared.PipelineFactoryCombination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// How the Made tab shows the combinations a Pipeline Factory has met: what each is called, what
/// became of its pipeline, and in what order they come.
public final class CombinationText {

    private CombinationText() {
        // Static methods only
    }

    /// Describes a combination by its key values, e.g. for a question about it.
    ///
    /// @param combination The combination.
    /// @return Its key values, separated by slashes.
    public static String describe(final PipelineFactoryCombination combination) {
        return String.join(" / ", combination.getValues().values());
    }

    /// Says what became of a combination's pipeline.
    ///
    /// @param combination The combination.
    /// @return "Made", "Made, then deleted", "Failed" or "Being made".
    public static String state(final PipelineFactoryCombination combination) {
        if (PipelineFactoryCombination.MADE.equals(combination.getState())) {
            return combination.isPipelineExists()
                    ? "Made"
                    : "Made, then deleted";
        } else if (PipelineFactoryCombination.FAILED.equals(combination.getState())) {
            return "Failed";
        }
        return "Being made";
    }

    /// Puts the combinations newest first: what a person comes to the Made tab to see is what the
    /// factory has just met.
    ///
    /// @param combinations The combinations, oldest first, as the factory gives them.
    /// @return A new list of them, newest first.
    public static List<PipelineFactoryCombination> newestFirst(final List<PipelineFactoryCombination> combinations) {
        final List<PipelineFactoryCombination> rows = new ArrayList<>(combinations);
        Collections.reverse(rows);
        return rows;
    }
}
