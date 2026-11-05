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
import java.util.regex.Pattern;

/// Normalises a bare `host` or `host:port`, as used by message-bus bootstrap lists. The host is folded to lower
/// case and a trailing dot is dropped.
public class HostPortNormaliser implements DestinationNormaliser {

    private static final Pattern PATTERN = Pattern.compile("([a-z0-9._-]+)(?::([0-9]{1,5}))?");

    @Override
    public Optional<NormalisedDestination> normalise(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        final java.util.regex.Matcher matcher = PATTERN.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String host = matcher.group(1);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return Optional.empty();
        }
        final String port = matcher.group(2);
        if (port != null) {
            final int number = Integer.parseInt(port);
            if (number < 1 || number > 65535) {
                return Optional.empty();
            }
            text = host + ":" + number;
        } else {
            text = host;
        }
        return Optional.of(new NormalisedDestination(text, List.of(text)));
    }
}
