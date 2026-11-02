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

package stroom.processor.api;

import java.util.Objects;

/// Thrown by a pipeline element to end the processing task it is running in as *deferred*: not done and
/// not failed, but to be tried again later, because what the stream needs is not there yet. The task goes
/// back to `CREATED` once a configured delay has passed, or sooner when whatever it waited for arrives;
/// nothing is written to the stream, no error is recorded against it, and the element is expected to have
/// produced no output before throwing.
///
/// It is unchecked so that it can pass through SAX handlers, and it is let through the places that turn
/// every other exception into a fatal error (`AbstractProcessorTaskExecutor`), which is what makes it a
/// third outcome rather than a failure with a different message.
public class DeferTaskException extends RuntimeException {

    /// @param reason Why the task is being put back, for the log and for whoever holds the reason: the task
    ///               row itself records only that it is deferred.
    public DeferTaskException(final String reason) {
        super(Objects.requireNonNull(reason, "A deferral needs a reason"));
    }

    /// @return Why the task was put back.
    public String getReason() {
        return getMessage();
    }

    /// The deferral inside a throwable, if there is one: the pipeline wraps what an element throws —
    /// `ProcessException` around `XPathException` around the cause — and a deferral must survive that to be
    /// recognised at the top.
    ///
    /// @param throwable What was caught.
    /// @return The deferral it is or carries, or null where it carries none.
    public static DeferTaskException unwrap(final Throwable throwable) {
        Throwable cause = throwable;
        // Bounded, since a cause chain may loop.
        for (int depth = 0; cause != null && depth < 32; depth++) {
            if (cause instanceof final DeferTaskException deferral) {
                return deferral;
            }
            cause = cause.getCause();
        }
        return null;
    }
}
