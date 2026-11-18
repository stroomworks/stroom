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

package stroom.shapeshifter.ai.quality;

import stroom.shapeshifter.shared.VolumeDay;

import java.util.Collection;
import java.util.List;

/// How much a learner's translations produced, day by day (UTC), as a Smart Pipeline's Quality Assurance tab
/// shows its volume: read from what each binding recorded of each stream it translated.
public interface Volumes {

    /// The days on which any of the bindings translated anything, since a time, oldest first. Days on which
    /// they translated nothing are not returned.
    ///
    /// @param bindingUuids The learner's bindings.
    /// @param sinceMs      The start of the window, in milliseconds since the epoch.
    /// @return A row per day that had any streams.
    List<VolumeDay> perDay(Collection<String> bindingUuids, long sinceMs);
}
