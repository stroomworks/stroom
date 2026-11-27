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

package stroom.learningflow.transformation;

import java.util.Map;
import java.util.Objects;

/// Facts about the stream being translated, which are all the context the allowed `stroom:` functions are
/// given when a candidate stylesheet is run outside a pipeline. Limiting them to this makes the functions
/// repeatable: judging and serving the same stream give the same answer.
///
/// A [stroom.learningflow.learning.Sample] carries these facts for the stream it was taken from.
///
/// @param id         The stream's id, or 0 where the harness has none.
/// @param feed       The feed's name, or null.
/// @param type       The stream type, or null.
/// @param receivedMs When the stream was received, which is the reference a partial date is completed
///                   from, or null where it is not known. Null does not mean "now": a stand-in asked to
///                   complete a date without it fails rather than reading the clock, because a date taken
///                   from the clock would differ between judging and serving.
/// @param attributes The receipt headers, which `stroom:meta` reads.
public record StreamFacts(long id,
                          String feed,
                          String type,
                          Long receivedMs,
                          Map<String, String> attributes) {

    public StreamFacts {
        attributes = attributes == null
                ? Map.of()
                : Map.copyOf(attributes);
    }

    /// Returns facts for a harness that knows nothing of the stream: every stand-in that needs the stream
    /// then reports that, rather than guessing.
    ///
    /// @return The empty facts.
    public static StreamFacts none() {
        return new StreamFacts(0L, null, null, null, Map.of());
    }

    /// Reads a receipt header, as `stroom:meta` does.
    ///
    /// @param name The header's name.
    /// @return Its value, or null where the stream does not carry it.
    public String attribute(final String name) {
        if (name == null) {
            return null;
        }
        final String direct = attributes.get(name);
        if (direct != null) {
            return direct;
        }
        // The real function also reads the stream's own fields under these names, as well as the headers
        return switch (name) {
            case "Feed" -> feed;
            case "Type", "StreamType" -> type;
            case "StreamId" -> id == 0L
                    ? null
                    : String.valueOf(id);
            default -> null;
        };
    }

    /// Returns when the stream was received, for completing a partial date.
    ///
    /// @return When the stream was received, in epoch milliseconds.
    /// @throws NullPointerException where it is not known, with a message saying what that prevents.
    public long requireReceivedMs() {
        return Objects.requireNonNull(receivedMs, () ->
                "This run does not know when the stream was received, so a date without a year cannot be "
                + "completed. Judging and serving must agree, so the time is taken from the stream and "
                + "never from the clock.");
    }
}
