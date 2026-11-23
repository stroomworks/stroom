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

package stroom.learningflow.element;

import stroom.data.store.api.AttributeMapFactory;
import stroom.data.store.api.InputStreamProvider;
import stroom.data.store.api.SegmentInputStream;
import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.feed.api.FeedProperties;
import stroom.learningflow.stage.Input;
import stroom.meta.api.MetaService;
import stroom.meta.shared.Meta;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Audit finding S3: **the worker reads a stream in the feed's encoding, as serving does.**
///
/// Learning happens in the worker (A60), which decoded every stream as UTF-8 whatever the feed said,
/// while the task path has always honoured the feed's encoding. So a feed in windows-1252 or UTF-16 was
/// learned from wrongly decoded text and then served correctly decoded: the parser was taught against
/// characters the stream does not contain.
class TestStreamInputsEncoding {

    private static final long META_ID = 7L;
    private static final String FEED = "ACCENTS";
    /// Text that decodes differently in the two encodings, and is a record a parser might cut.
    private static final String TEXT = "2026-01-01,Zoë,café,logon\n";

    @Test
    void aFeedInASingleByteEncodingIsLearnedFromTheRightCharacters() throws Exception {
        final Input input = inputs(TEXT.getBytes(Charset.forName("windows-1252")), "windows-1252")
                .byId(META_ID).orElseThrow();

        assertThat(input.data()).isEqualTo(TEXT);
    }

    @Test
    void aFeedInUtf16IsReadableAtAll() throws Exception {
        // Read as UTF-8 this is not text a parser could cut records from at all
        final Input input = inputs(TEXT.getBytes(StandardCharsets.UTF_16), "UTF-16")
                .byId(META_ID).orElseThrow();

        assertThat(input.data()).isEqualTo(TEXT);
    }

    @Test
    void aFeedWithNoEncodingOfItsOwnIsStillUtf8() throws Exception {
        final Input input = inputs(TEXT.getBytes(StandardCharsets.UTF_8), null)
                .byId(META_ID).orElseThrow();

        assertThat(input.data()).isEqualTo(TEXT);
    }

    @Test
    void anEncodingThisNodeCannotUseIsAReasonToGoOnInUtf8() throws Exception {
        // Not a reason to abandon the stream: the pipeline does the same, and most feeds are UTF-8
        final Input input = inputs(TEXT.getBytes(StandardCharsets.UTF_8), "no-such-charset")
                .byId(META_ID).orElseThrow();

        assertThat(input.data()).isEqualTo(TEXT);
    }

    // --------------------------------------------------------------------------------


    /// A stream of bytes as the data store hands one over: one segment, read straight through.
    private static final class BytesAsSegments extends SegmentInputStream {

        private final ByteArrayInputStream bytes;
        private final long length;

        private BytesAsSegments(final byte[] bytes) {
            this.bytes = new ByteArrayInputStream(bytes);
            this.length = bytes.length;
        }

        @Override
        public long count() {
            return 1;
        }

        @Override
        public void include(final long segment) {
        }

        @Override
        public void includeAll() {
        }

        @Override
        public void exclude(final long segment) {
        }

        @Override
        public void excludeAll() {
        }

        @Override
        public long size() {
            return length;
        }

        @Override
        public int read() {
            return bytes.read();
        }

        @Override
        public int read(final byte[] buffer, final int off, final int len) {
            return bytes.read(buffer, off, len);
        }
    }

    /// @param bytes    The stream's bytes.
    /// @param encoding What the feed says it is written in, or null for none.
    /// @return A `StreamInputs` over a stream of those bytes.
    private static StreamInputs inputs(final byte[] bytes, final String encoding) throws Exception {
        final Meta meta = mock(Meta.class);
        when(meta.getId()).thenReturn(META_ID);
        when(meta.getFeedName()).thenReturn(FEED);
        when(meta.getTypeName()).thenReturn("Raw Events");
        when(meta.getCreateMs()).thenReturn(0L);

        final MetaService metaService = mock(MetaService.class);
        when(metaService.getMeta(META_ID)).thenReturn(meta);

        final InputStreamProvider provider = mock(InputStreamProvider.class);
        when(provider.get()).thenReturn(new BytesAsSegments(bytes));
        final Source source = mock(Source.class);
        when(source.get(0)).thenReturn(provider);
        final Store store = mock(Store.class);
        when(store.openSource(META_ID)).thenReturn(source);

        final AttributeMapFactory attributes = mock(AttributeMapFactory.class);
        when(attributes.getAttributes(anyLong())).thenReturn(null);

        final FeedProperties feeds = mock(FeedProperties.class);
        when(feeds.getEncoding(any(), any(), any())).thenReturn(encoding);

        return new StreamInputs(store, metaService, attributes, feeds);
    }
}
