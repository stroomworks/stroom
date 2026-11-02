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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The strict grammar for a reply to the chain question (A21 step 1): element type names, in chain order,
 * separated by newlines, commas or arrows, and drawn only from the allowed set. Anything else — prose, an
 * element the document does not allow, an empty chain — is refused rather than guessed at, exactly as
 * {@link ConfigurationReply} refuses an ambiguous document.
 */
public final class ChainReply {

    private static final String SEPARATORS = "[\\s,>\\u2192-]+";

    private ChainReply() {
    }

    public static Optional<List<String>> chain(final String reply, final Set<String> allowedElements) {
        if (reply == null) {
            return Optional.empty();
        }
        final List<String> chain = new ArrayList<>();
        for (final String token : reply.strip().split(SEPARATORS)) {
            if (token.isEmpty()) {
                continue;
            }
            if (!allowedElements.contains(token)) {
                return Optional.empty();
            }
            chain.add(token);
        }
        return chain.isEmpty()
                ? Optional.empty()
                : Optional.of(List.copyOf(chain));
    }
}
