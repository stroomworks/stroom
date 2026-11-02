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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The strict grammar for what comes back from a model (design §10). A reply must be a single configuration
 * document and nothing else: either exactly one fenced code block, whose contents are the document, or
 * the bare document with no fence at all. Anything else — prose with no document, two candidate blocks, an
 * unclosed fence — is refused rather than guessed at. A refusal is a failed attempt with feedback, not an
 * error.
 */
public final class ConfigurationReply {

    private static final Pattern FENCED_BLOCK = Pattern.compile(
            "```[\\w-]*[ \\t]*\\R(.*?)\\R[ \\t]*```",
            Pattern.DOTALL);
    private static final String FENCE = "```";

    private ConfigurationReply() {
    }

    public static Optional<String> configuration(final String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        final Matcher matcher = FENCED_BLOCK.matcher(reply);
        if (matcher.find()) {
            final String configuration = matcher.group(1);
            return matcher.find()
                    ? Optional.empty()
                    : Optional.of(configuration);
        }
        if (reply.contains(FENCE)) {
            return Optional.empty();
        }
        final String bare = reply.strip();
        return bare.startsWith("<")
                ? Optional.of(bare)
                : Optional.empty();
    }
}
