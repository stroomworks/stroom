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

package stroom.learningflow.stage;

import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LogUtil;

/// Writes a session's record without letting a failure to write it fail the work it records. A session's
/// row is written after the fragment, binding and output it describes, so a database error on that row must
/// not undo a stream that succeeded or a binding change that has been made.
final class Bookkeeping {

    private Bookkeeping() {
    }

    /// Writes a record, logging an error rather than throwing if it cannot be written.
    ///
    /// @param logger The caller's logger, so that a failure is logged under the class that wrote it.
    /// @param write  The action that writes the record.
    static void record(final LambdaLogger logger, final Runnable write) {
        try {
            write.run();
        } catch (final RuntimeException e) {
            logger.error(() -> LogUtil.message("The attempt could not be recorded: {}", e.getMessage()), e);
        }
    }
}
