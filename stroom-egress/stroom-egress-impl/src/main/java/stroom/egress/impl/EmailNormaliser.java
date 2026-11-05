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

package stroom.egress.impl;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/// Normalises a single email address. The domain is folded to lower case, and angle-bracket, multi-address and
/// whitespace forms are rejected.
public class EmailNormaliser implements DestinationNormaliser {

    @Override
    public Optional<NormalisedDestination> normalise(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String text = raw.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '<' || c == '>' || c == ',' || c == ';' || c == '"') {
                return Optional.empty();
            }
        }
        final int at = text.indexOf('@');
        if (at < 1 || at != text.lastIndexOf('@') || at == text.length() - 1) {
            return Optional.empty();
        }
        final String domain = text.substring(at + 1).toLowerCase(Locale.ROOT);
        if (domain.startsWith(".") || domain.endsWith(".") || !domain.contains(".")) {
            return Optional.empty();
        }
        final String canonical = text.substring(0, at) + "@" + domain;
        return Optional.of(new NormalisedDestination(canonical, List.of(canonical)));
    }
}
