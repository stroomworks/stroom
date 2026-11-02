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

package stroom.shapeshifter.ai.learning;

import java.util.Optional;

/**
 * What one kind of input record should become (design 01 §10.1, ruling A31): the representative record
 * as the split cut it, and the event the model proposed for it — validated before anything downstream
 * was written — or nothing, for a kind of record that yields no event (a header line, a comment).
 *
 * @param record The representative input record's text, exactly as the split yielded it.
 * @param event  The event as a single document, or empty where the kind yields none.
 */
public record Target(String record, Optional<String> event) {

    public static Target none(final String record) {
        return new Target(record, Optional.empty());
    }

    public static Target of(final String record, final String event) {
        return new Target(record, Optional.of(event));
    }
}
