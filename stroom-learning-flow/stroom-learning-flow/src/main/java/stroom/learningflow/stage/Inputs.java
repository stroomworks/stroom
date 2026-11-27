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

import stroom.util.shared.TextRange;

import java.util.List;
import java.util.Optional;

/// Looks up the stream a session was opened on, by the meta id recorded on the session's row. This is what
/// lets a session be resumed outside the task that opened it: the worker replays the conversation over the
/// same sample, which is read from the stream itself. The stream's text is not copied into the session,
/// because the stream is already stored and storing its text again would duplicate it.
///
/// A node reads the stream store; tests answer from what they put there.
public interface Inputs {

    /// Gets the stream, or empty if it has since been deleted. A learner whose only example is gone cannot
    /// be learned, so the caller closes the session rather than leaving it waiting.
    Optional<Input> byId(long metaId);

    /// Gets the text between two locations, each a line and column counted from one, with the end
    /// location's character included. Empty if the span is missing or out of range.
    static Optional<String> cut(final String data, final TextRange span) {
        if (data == null || span == null || span.getFrom() == null || span.getTo() == null) {
            return Optional.empty();
        }
        final List<String> lines = data.lines().toList();
        final int firstLine = span.getFrom().getLineNo() - 1;
        final int lastLine = span.getTo().getLineNo() - 1;
        if (firstLine < 0 || lastLine < firstLine || lastLine >= lines.size()) {
            return Optional.empty();
        }
        final StringBuilder text = new StringBuilder();
        for (int line = firstLine; line <= lastLine; line++) {
            final String whole = lines.get(line);
            final int from = line == firstLine
                    ? Math.min(Math.max(span.getFrom().getColNo() - 1, 0), whole.length())
                    : 0;
            // The end column is the last character of the record, so it is included.
            final int to = line == lastLine
                    ? Math.min(Math.max(span.getTo().getColNo(), from), whole.length())
                    : whole.length();
            text.append(whole, from, to);
            if (line < lastLine) {
                text.append('\n');
            }
        }
        return Optional.of(text.toString());
    }
}
