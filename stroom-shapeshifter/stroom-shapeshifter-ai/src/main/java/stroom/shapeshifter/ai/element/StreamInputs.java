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

package stroom.shapeshifter.ai.element;

import stroom.data.store.api.AttributeMapFactory;
import stroom.data.store.api.InputStreamProvider;
import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.feed.api.FeedProperties;
import stroom.meta.api.MetaService;
import stroom.meta.shared.Meta;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Inputs;
import stroom.shapeshifter.ai.stage.RecordSkeleton;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/// The streams of A28 in a node: the stream store, read by meta id. This is how the worker
/// gets back the sample an attempt was learning from — the attempt keeps the id and the store keeps the
/// stream, so nothing is copied and nothing is held.
///
/// The first part only, as the supervisor element sees it: a stream of several parts is served part by
/// part and it is the first that raised the attempt (design 02 §7).
@Singleton
public class StreamInputs implements Inputs {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StreamInputs.class);

    private final Store store;
    private final MetaService metaService;
    private final AttributeMapFactory attributeMapFactory;
    private final FeedProperties feedProperties;

    @Inject
    public StreamInputs(final Store store,
                        final MetaService metaService,
                        final AttributeMapFactory attributeMapFactory,
                        final FeedProperties feedProperties) {
        this.store = store;
        this.metaService = metaService;
        this.attributeMapFactory = attributeMapFactory;
        this.feedProperties = feedProperties;
    }

    @Override
    public Optional<Input> byId(final long metaId) {
        final Meta meta = metaService.getMeta(metaId);
        if (meta == null) {
            return Optional.empty();
        }
        try (final Source source = store.openSource(metaId)) {
            final Map<String, String> attributes = attributeMapFactory.getAttributes(metaId);
            // Read as far as the prefix (design 04 §3): what a session samples from is bounded by the
            // document far below this, and the whole of a stream of any size is read only by a fragment
            // running over it, through the opener, which opens the stream again.
            final Prefix prefix = prefix(source, encodingOf(meta));
            // When the stream was received, which is what a timestamp with no year takes its year from
            // and what the times check measures an event against (Track 1 task 3)
            final Long receivedMs = meta.getCreateMs();
            return Optional.of((prefix.complete()
                    ? new Input(metaId, meta.getFeedName(), meta.getTypeName(), attributes == null
                            ? Map.of()
                            : attributes, prefix.text())
                    : Input.streamed(metaId, meta.getFeedName(), meta.getTypeName(), attributes == null
                            ? Map.of()
                            : attributes, prefix.text(), null, () -> open(metaId)))
                    .receivedAt(receivedMs));
        } catch (final Exception e) {
            // Deleted, on a volume this node cannot reach, or unreadable — and the close of the source
            // throws a checked exception besides. The attempt that was learning from it is abandoned by
            // the caller rather than left waiting for a stream that will not come.
            LOGGER.warn(() -> LogUtil.message("Stream {} could not be read: {}", metaId, e.getMessage()), e);
            return Optional.empty();
        }
    }

    /// The first [Input#PREFIX_BYTES] of the stream as text, and whether that was all of it. A prefix that
    /// is not the whole stream ends at the last line break within it: a line cut in the middle is not a
    /// record of the stream, and judged as one it would fail a candidate that every whole record clears.
    ///
    /// @param source   The stream.
    /// @param encoding What the feed says its data is written in.
    private static Prefix prefix(final Source source, final Charset encoding) throws IOException {
        try (final InputStreamProvider provider = source.get(0)) {
            final InputStream in = provider.get();
            final byte[] bytes = in.readNBytes(Input.PREFIX_BYTES);
            final boolean complete = bytes.length < Input.PREFIX_BYTES || in.read() < 0;
            final String text = RecordSkeleton.withoutBom(new String(bytes, encoding));
            return new Prefix(complete
                    ? text
                    : wholeLines(text), complete);
        }
    }

    /// What the feed says its data is written in (audit finding S3).
    ///
    /// Learning happens in the worker (A60), which read every stream as UTF-8 whatever the feed said,
    /// while the task path has always honoured the feed's encoding
    /// (`Supervision` through `InputSource.getEncoding()`). So a feed in UTF-16 or windows-1252 was
    /// learned from wrongly decoded text and then served correctly decoded — the parser learned against
    /// characters the stream does not contain.
    ///
    /// An encoding the node cannot name is a reason to go on in UTF-8 rather than to abandon the stream:
    /// the same thing the pipeline does with an unreadable encoding, and most feeds are UTF-8 anyway.
    ///
    /// @param meta The stream's meta.
    /// @return The charset.
    private Charset encodingOf(final Meta meta) {
        try {
            final String named = feedProperties.getEncoding(meta.getFeedName(), meta.getTypeName(), null);
            return named == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(named);
        } catch (final UnsupportedEncodingException | RuntimeException e) {
            LOGGER.warn(() -> LogUtil.message("Feed {} names an encoding this node cannot use ({}); "
                                              + "stream {} is read as UTF-8 to learn from",
                    meta.getFeedName(), e.getMessage(), meta.getId()));
            return StandardCharsets.UTF_8;
        }
    }

    /// The text up to and including its last line break, or all of it where it has none.
    private static String wholeLines(final String text) {
        final int lastBreak = text.lastIndexOf('\n');
        return lastBreak < 0
                ? text
                : text.substring(0, lastBreak + 1);
    }

    /// The whole stream, opened afresh, closing its source with it.
    private InputStream open(final long metaId) {
        try {
            final Source source = store.openSource(metaId);
            final InputStreamProvider provider = source.get(0);
            return new FilterInputStream(provider.get()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        provider.close();
                        source.close();
                    }
                }
            };
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Prefix(String text, boolean complete) {

    }
}
