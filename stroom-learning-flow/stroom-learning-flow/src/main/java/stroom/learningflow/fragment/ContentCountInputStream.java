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

package stroom.learningflow.fragment;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/// An input stream wrapper that counts the bytes that are **content**, i.e. everything but ASCII
/// whitespace.
///
/// It is used to tell an empty stream apart from one the parser could make nothing of. Counting every
/// byte would not work: a stream holding one newline has a byte in it but no content, and treating that
/// as "the translation can no longer read this feed" would defer the stream and pay for relearning.
///
/// ASCII whitespace is safe to skip over UTF-8 without decoding, because every byte of a multi-byte
/// sequence has its high bit set and so can never be mistaken for one.
final class ContentCountInputStream extends FilterInputStream {

    private long content;

    ContentCountInputStream(final InputStream wrapped) {
        super(wrapped);
    }

    /// How many bytes of content have been read.
    long content() {
        return content;
    }

    @Override
    public int read() throws IOException {
        final int read = in.read();
        if (read >= 0) {
            count((byte) read);
        }
        return read;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        final int read = in.read(buffer, offset, length);
        for (int i = 0; i < read; i++) {
            count(buffer[offset + i]);
        }
        return read;
    }

    /// Counts the byte if it is not ASCII whitespace.
    ///
    /// @param b A byte that was read.
    private void count(final byte b) {
        if (b != ' ' && b != '\t' && b != '\n' && b != '\r' && b != '\f' && b != 0x0B) {
            content++;
        }
    }
}
