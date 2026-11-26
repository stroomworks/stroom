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

package stroom.learningflow.state;

import stroom.learningflow.quality.Volumes;
import stroom.learningflow.shared.VolumeDay;

import java.util.Collection;
import java.util.List;

/// An in-memory [Volumes] that always reports no volume. The scenarios run streams to judge what is
/// learned, not to count them; a node counts them from its output rows.
public class InMemoryVolumes implements Volumes {

    @Override
    public List<VolumeDay> perDay(final Collection<String> bindingUuids, final long sinceMs) {
        return List.of();
    }
}
