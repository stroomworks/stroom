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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/// Normalises a file path: it must be absolute, `.` and `..` are resolved, and links are resolved on the nearest
/// ancestor that exists, so that a link inside an allowed directory cannot lead outside it. The check can therefore
/// be made before any directory or lock file is created.
public class FileNormaliser implements DestinationNormaliser {

    @Override
    public Optional<NormalisedDestination> normalise(final String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        final Path path;
        try {
            path = Paths.get(raw.trim());
        } catch (final InvalidPathException e) {
            return Optional.empty();
        }
        if (!path.isAbsolute()) {
            return Optional.empty();
        }
        final Path resolved;
        try {
            resolved = resolveLinks(path.normalize());
        } catch (final IOException e) {
            return Optional.empty();
        }
        final List<String> components = new ArrayList<>();
        for (final Path name : resolved) {
            components.add(name.toString());
        }
        final String text = "/" + String.join("/", components);
        return Optional.of(new NormalisedDestination(text, List.copyOf(components)));
    }

    // Resolves links on the longest prefix that exists and appends the rest unchanged. A dangling link is an error.
    private static Path resolveLinks(final Path normalised) throws IOException {
        final Deque<Path> tail = new ArrayDeque<>();
        Path existing = normalised;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            tail.push(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalised;
        }
        Path result = existing.toRealPath();
        while (!tail.isEmpty()) {
            result = result.resolve(tail.pop());
        }
        return result;
    }
}
