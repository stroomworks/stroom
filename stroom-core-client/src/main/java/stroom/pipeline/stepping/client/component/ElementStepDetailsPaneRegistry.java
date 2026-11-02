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

package stroom.pipeline.stepping.client.component;

import com.google.inject.Provider;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Singleton;

/// The panes element types show on the stepping screen in place of a code pane (A30), registered by the
/// areas that own those element types, so that stepping knows none of them.
@Singleton
public class ElementStepDetailsPaneRegistry {

    private final Map<String, Provider<ElementStepDetailsPane>> panes = new HashMap<>();

    /// Registers the pane an element type shows.
    ///
    /// @param elementType The element type.
    /// @param pane        Makes the pane, one for each element shown.
    public void register(final String elementType, final Provider<ElementStepDetailsPane> pane) {
        panes.put(elementType, pane);
    }

    /// @param elementType The element type.
    /// @return What makes the type's pane, or `null` where none is registered for it.
    public Provider<ElementStepDetailsPane> get(final String elementType) {
        return panes.get(elementType);
    }
}
