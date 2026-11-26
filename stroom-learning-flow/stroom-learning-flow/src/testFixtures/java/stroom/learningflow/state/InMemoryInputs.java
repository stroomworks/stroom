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

import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Inputs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// An in-memory [Inputs], from which the background worker reads streams, for tests with no stream store.
/// The streams a scenario puts through a stage are the ones its worker reads back.
public final class InMemoryInputs implements Inputs {

    private final Map<Long, Input> inputs = new LinkedHashMap<>();

    /// Stores a stream, as receiving one does.
    public synchronized Input put(final Input input) {
        inputs.put(input.id(), input);
        return input;
    }

    @Override
    public synchronized Optional<Input> byId(final long metaId) {
        return Optional.ofNullable(inputs.get(metaId));
    }

    /// Removes a stream, as deleting it or its expiry under retention does.
    public synchronized void remove(final long metaId) {
        inputs.remove(metaId);
    }
}
