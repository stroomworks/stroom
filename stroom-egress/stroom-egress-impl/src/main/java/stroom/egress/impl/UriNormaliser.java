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

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// Normalises destinations that are written as `scheme://host[:port]/path`.
///
/// Scheme and host are folded to lower case and the path never is. A trailing dot, an IDN and the default port are
/// normalised. User-info, a backslash, `;` parameters, `%00`, `%2F` and `%5C` inside a segment, and a double-encoded
/// value are rejected. Percent-decoding happens once. The fragment and the query are dropped, so they take no part in
/// a match and are never stored.
public class UriNormaliser implements DestinationNormaliser {

    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443);
    private static final Pattern ENCODED = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern HOST = Pattern.compile("[a-z0-9._-]+");
    private static final String UNRESERVED_EXTRA = "-._~!$&'()*+,=:@";

    private final Set<String> schemes;

    /// Creates a normaliser.
    ///
    /// @param schemes The schemes it accepts, in lower case.
    public UriNormaliser(final Set<String> schemes) {
        this.schemes = schemes;
    }

    @Override
    public Optional<NormalisedDestination> normalise(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String text = raw.trim();
        if (text.isEmpty() || hasForbiddenCharacter(text)) {
            return Optional.empty();
        }
        final int schemeEnd = text.indexOf("://");
        if (schemeEnd < 1) {
            return Optional.empty();
        }
        final String scheme = text.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!schemes.contains(scheme)) {
            return Optional.empty();
        }
        String rest = text.substring(schemeEnd + 3);
        final int fragment = rest.indexOf('#');
        if (fragment >= 0) {
            rest = rest.substring(0, fragment);
        }
        final int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        final int slash = rest.indexOf('/');
        final String authority = slash < 0
                ? rest
                : rest.substring(0, slash);
        final String path = slash < 0
                ? ""
                : rest.substring(slash);

        final Optional<String> normalisedAuthority = normaliseAuthority(scheme, authority);
        if (normalisedAuthority.isEmpty()) {
            return Optional.empty();
        }
        final Optional<List<String>> segments = normalisePath(path);
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        final String root = scheme + "://" + normalisedAuthority.get();
        final List<String> components = new ArrayList<>();
        components.add(root);
        components.addAll(segments.get());
        final String canonical = segments.get().isEmpty()
                ? root + "/"
                : root + "/" + String.join("/", segments.get());
        return Optional.of(new NormalisedDestination(canonical, List.copyOf(components)));
    }

    private static boolean hasForbiddenCharacter(final String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < 0x21 || c == 0x7f || c == '\\') {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> normaliseAuthority(final String scheme, final String authority) {
        if (authority.isEmpty() || authority.indexOf('@') >= 0) {
            return Optional.empty();
        }
        String host = authority;
        String port = null;
        if (authority.startsWith("[")) {
            final int close = authority.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            host = authority.substring(0, close + 1).toLowerCase(Locale.ROOT);
            final String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    return Optional.empty();
                }
                port = after.substring(1);
            }
        } else {
            final int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            }
            host = host.toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            try {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (final IllegalArgumentException e) {
                return Optional.empty();
            }
            if (!HOST.matcher(host).matches()) {
                return Optional.empty();
            }
        }
        if (host.isEmpty()) {
            return Optional.empty();
        }
        if (port == null) {
            return Optional.of(host);
        }
        final int portNumber;
        try {
            if (port.isEmpty() || port.length() > 5 || !port.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            portNumber = Integer.parseInt(port);
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
        if (portNumber < 1 || portNumber > 65535) {
            return Optional.empty();
        }
        // Only an https port 443 or an http port 80 is the default. https on 80 is not.
        final Integer defaultPort = DEFAULT_PORTS.get(scheme);
        return Optional.of(defaultPort != null && defaultPort == portNumber
                ? host
                : host + ":" + portNumber);
    }

    private static Optional<List<String>> normalisePath(final String path) {
        final List<String> result = new ArrayList<>();
        if (path.isEmpty()) {
            return Optional.of(result);
        }
        if (path.indexOf(';') >= 0) {
            return Optional.empty();
        }
        for (final String raw : path.split("/", -1)) {
            if (raw.isEmpty()) {
                continue;
            }
            final Optional<String> decoded = decodeOnce(raw);
            if (decoded.isEmpty()) {
                return Optional.empty();
            }
            final String segment = decoded.get();
            if (".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (result.isEmpty()) {
                    return Optional.empty();
                }
                result.remove(result.size() - 1);
                continue;
            }
            result.add(encode(segment));
        }
        return Optional.of(result);
    }

    // Decodes percent escapes once. Rejects an escaped slash, backslash or NUL, a bad escape, and anything that
    // is still escaped after decoding (a double-encoded value).
    private static Optional<String> decodeOnce(final String raw) {
        final String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("%00") || lower.contains("%2f") || lower.contains("%5c")) {
            return Optional.empty();
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length()) {
            final int codePoint = raw.codePointAt(i);
            if (codePoint == '%') {
                if (i + 2 >= raw.length()) {
                    return Optional.empty();
                }
                final String hex = raw.substring(i + 1, i + 3);
                if (!ENCODED.matcher("%" + hex).matches()) {
                    return Optional.empty();
                }
                bytes.write(Integer.parseInt(hex, 16));
                i += 3;
            } else {
                final byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i += Character.charCount(codePoint);
            }
        }
        final String decoded = bytes.toString(StandardCharsets.UTF_8);
        if (ENCODED.matcher(decoded).find()) {
            return Optional.empty();
        }
        for (int j = 0; j < decoded.length(); j++) {
            final char c = decoded.charAt(j);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\') {
                return Optional.empty();
            }
        }
        return Optional.of(decoded);
    }

    private static String encode(final String segment) {
        final StringBuilder out = new StringBuilder();
        for (final byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            final int value = b & 0xff;
            final char c = (char) value;
            if (value < 0x80 && (Character.isLetterOrDigit(c) || UNRESERVED_EXTRA.indexOf(c) >= 0)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", value));
            }
        }
        return out.toString();
    }
}
