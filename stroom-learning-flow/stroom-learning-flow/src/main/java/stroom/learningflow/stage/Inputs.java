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

/// The streams an attempt was raised on, by the meta id its row names (A28): what lets an attempt be
/// carried on outside the task that raised it. The worker re-walks the conversation over the
/// same sample, and the sample is the stream, which is kept where every stream is kept rather than
/// copied into the attempt — a stream is kept once, and storing its text again would be storing it
/// twice. (It used to say "until redaction is built (A17, A38)"; A146 settled that samples are never
/// redacted, so the reason is simply that the stream is already there.)
///
/// A node reads the stream store; a scenario answers from what it put there.
public interface Inputs {

    /// The stream, or empty where it has been deleted since — a learner whose only example is gone cannot
    /// be learned, and the attempt that was learning it is closed rather than left waiting.
    Optional<Input> byId(long metaId);

    /// The text between two locations, each a line and a column counted from one.
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
