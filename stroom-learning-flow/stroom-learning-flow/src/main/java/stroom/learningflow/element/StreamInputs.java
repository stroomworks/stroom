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
import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.feed.api.FeedProperties;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Inputs;
import stroom.learningflow.stage.RecordSkeleton;
import stroom.meta.api.MetaService;
import stroom.meta.shared.Meta;
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

/// The node's implementation of [Inputs], which reads a stream from the stream store by meta id. This is
/// how the background worker gets back the stream an attempt is learning from: the attempt keeps only the
/// id and the store keeps the stream, so nothing is copied or held.
///
/// Only the first part is read, as the Smart Pipeline element sees it: a stream of several parts is
/// served part by part, and it is the first part that opened the attempt.
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
            // Read only the prefix: the sample a session takes is limited by the document to far less
            // than this, and the whole of a large stream is read only by a fragment running over it,
            // through the opener, which opens the stream again.
            final Prefix prefix = prefix(source, encodingOf(meta));
            // When the stream was received, which a timestamp with no year takes its year from, and which
            // the times check measures an event against.
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
            // The stream was deleted, is on a volume this node cannot reach, or is unreadable (and closing
            // the source can also throw a checked exception). The caller abandons the attempt that was
            // learning from it rather than leaving it waiting for a stream that will never come.
            LOGGER.warn(() -> LogUtil.message("Stream {} could not be read: {}", metaId, e.getMessage()), e);
            return Optional.empty();
        }
    }

    /// Reads the first [Input#PREFIX_BYTES] of the stream as text, and whether that was all of it. A prefix
    /// that is not the whole stream is cut at its last line break, because a line cut in the middle is
    /// not a real record, and judging it as one would fail a candidate that every whole record passes.
    ///
    /// @param source   The stream.
    /// @param encoding The character encoding the feed says its data uses.
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

    /// Gets the character encoding the feed says its data uses.
    ///
    /// The processing task path decodes a stream using the feed's encoding (`Supervision`, through
    /// `InputSource.getEncoding()`), so the background worker must do the same. Otherwise a feed in UTF-16
    /// or windows-1252 would be learned from wrongly decoded text and then served correctly decoded, so
    /// the parser would have learned from characters the stream does not contain.
    ///
    /// An encoding the node does not recognise falls back to UTF-8 rather than abandoning the stream,
    /// which is what the pipeline does with an unreadable encoding, and most feeds are UTF-8 anyway.
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

    /// Opens the whole stream again, returning a stream that closes its source when it is closed.
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
