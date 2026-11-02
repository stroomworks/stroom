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

package stroom.pipeline.factory;

import stroom.pipeline.SupportsCodeInjection;
import stroom.util.pipeline.scope.PipelineScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/// The configuration an element is to run with in place of the document it references, by element id:
/// a stylesheet, a Data Splitter configuration, a fragment wrapper. Elements that can take one implement
/// [SupportsCodeInjection], and [PipelineFactory] hands it to them as it builds them.
///
/// Stepping has always been able to do this — a person edits the code in the stepper and the pipeline
/// runs with their edit rather than what is stored — but until now the only way to say so was to be a
/// stepping session, since the factory read the code from the stepping request. Anything else wanting to
/// run a configuration that is not in the store had to fake a session or write the document first, and
/// writing before running is what a supervisor that judges a candidate before promoting it must not do.
///
/// So the carrier is its own thing, scoped to the pipeline being built and set by whoever is building it:
/// the stepper from the person's edits, the Shapeshifter AI supervisor from a candidate it is judging.
/// Empty for an ordinary processing run, which is every run that is neither.
@PipelineScoped
public class InjectedCode {

    private final Map<String, String> byElementId = new HashMap<>();

    /// The code for one element, or empty where it is to run with what it references.
    ///
    /// @param elementId The element's id within the pipeline, as {@code PipelineElement.getId()} gives it.
    public Optional<String> get(final String elementId) {
        return Optional.ofNullable(byElementId.get(elementId));
    }

    /// Replaces what is held: a build takes the code it was given and nothing from a build before it.
    ///
    /// @param code Element id to configuration text. Null, and null values within it, are ignored — a
    ///             stepping request carries no code until a person edits something.
    public void set(final Map<String, String> code) {
        byElementId.clear();
        if (code != null) {
            code.forEach((elementId, text) -> {
                if (elementId != null && text != null) {
                    byElementId.put(elementId, text);
                }
            });
        }
    }

    /// What is held, so that something borrowing this carrier for one build can give back what it
    /// found: a supervisor judging a candidate inside a stepping session must not wipe the edits the
    /// person stepping is running with.
    public Map<String, String> asMap() {
        return Map.copyOf(byElementId);
    }

    /// Whether any element is to run with code in place of its document.
    public boolean isEmpty() {
        return byElementId.isEmpty();
    }

    @Override
    public String toString() {
        return "InjectedCode for " + byElementId.keySet();
    }
}
