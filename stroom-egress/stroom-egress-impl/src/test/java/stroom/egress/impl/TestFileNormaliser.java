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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestFileNormaliser {

    private final FileNormaliser normaliser = new FileNormaliser();

    @Test
    void testRelativeIsRejected() {
        assertThat(normaliser.normalise("out/file.dat")).isEmpty();
    }

    @Test
    void testNulIsRejected() {
        assertThat(normaliser.normalise("/tmp/a\0b")).isEmpty();
    }

    @Test
    void testDotDotIsResolved(@TempDir final Path dir) throws IOException {
        final Path real = dir.toRealPath();
        assertThat(normaliser.normalise(real + "/a/../b/./c.dat").orElseThrow().text())
                .isEqualTo(real + "/b/c.dat");
    }

    @Test
    void testDotDotCannotEscapeAPrefix(@TempDir final Path dir) throws IOException {
        final Path real = dir.toRealPath();
        final String escaped = normaliser.normalise(real + "/out/../../etc/passwd").orElseThrow().text();
        assertThat(escaped).isEqualTo(real.getParent() + "/etc/passwd");
    }

    @Test
    void testLinkIsResolvedOnTheNearestExistingAncestor(@TempDir final Path dir) throws IOException {
        final Path real = dir.toRealPath();
        final Path allowed = Files.createDirectory(real.resolve("allowed"));
        final Path outside = Files.createDirectory(real.resolve("outside"));
        final Path link = Files.createSymbolicLink(allowed.resolve("link"), outside);
        assertThat(normaliser.normalise(link + "/not/yet/created.dat").orElseThrow().text())
                .isEqualTo(outside + "/not/yet/created.dat");
    }

    @Test
    void testNonExistentPathIsAcceptedWithoutCreatingAnything(@TempDir final Path dir) throws IOException {
        final Path real = dir.toRealPath();
        final Path target = real.resolve("no/such/dir/file.dat");
        assertThat(normaliser.normalise(target.toString())).isPresent();
        assertThat(Files.exists(real.resolve("no"))).isFalse();
    }

    @Test
    void testDanglingLinkIsRejected(@TempDir final Path dir) throws IOException {
        final Path real = dir.toRealPath();
        final Path link = Files.createSymbolicLink(real.resolve("dangling"), real.resolve("missing"));
        assertThat(normaliser.normalise(link + "/file.dat")).isEmpty();
    }
}
