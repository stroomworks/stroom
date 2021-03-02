/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.receive;


import stroom.data.shared.StreamTypeNames;
import stroom.data.store.mock.MockStore;
import stroom.data.zip.StroomZipEntry;
import stroom.data.zip.StroomZipFileType;
import stroom.docref.DocRef;
import stroom.feed.api.FeedProperties;
import stroom.feed.api.FeedStore;
import stroom.feed.shared.FeedDoc;
import stroom.meta.api.AttributeMap;
import stroom.meta.api.StandardHeaderArguments;
import stroom.receive.common.StreamTargetStreamHandlers;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.test.common.util.test.FileSystemTestUtil;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

class TestStreamTargetStreamHandler extends AbstractProcessIntegrationTest {

    @Inject
    private MockStore streamStore;
    @Inject
    private FeedProperties feedProperties;
    @Inject
    private FeedStore feedStore;
    @Inject
    private StreamTargetStreamHandlers streamTargetStreamHandlers;

    /**
     * This test is used to check that feeds that are set to be reference feeds
     * do not aggregate streams.
     *
     * @throws IOException
     */
    @Test
    void testReferenceNonAggregation() throws IOException {
        streamStore.clear();

        final String feedName = FileSystemTestUtil.getUniqueTestString();
        final DocRef feedRef = feedStore.createDocument(feedName);
        final FeedDoc feedDoc = feedStore.readDocument(feedRef);
        feedDoc.setReference(true);
        feedStore.writeDocument(feedDoc);

        final AttributeMap attributeMap = new AttributeMap();
        attributeMap.put(StandardHeaderArguments.FEED, feedName);
        attributeMap.put(StandardHeaderArguments.TYPE, StreamTypeNames.RAW_REFERENCE);

        streamTargetStreamHandlers.handle(attributeMap, handler -> {
            try {
                handler.addEntry("1" + StroomZipFileType.META.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("1" + StroomZipFileType.CONTEXT.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("1" + StroomZipFileType.DATA.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.META.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.CONTEXT.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.DATA.getExtension(), new ByteArrayInputStream(new byte[0]));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(streamStore.getStreamStoreCount()).isEqualTo(2);
    }

    /**
     * This test is used to check that separate streams are created if the feed
     * changes.
     *
     * @throws IOException
     */
    @Test
    void testFeedChange() throws IOException {
        streamStore.clear();

        final String feedName1 = FileSystemTestUtil.getUniqueTestString();
        final AttributeMap attributeMap1 = new AttributeMap();
        attributeMap1.put(StandardHeaderArguments.FEED, feedName1);
        attributeMap1.put(StandardHeaderArguments.TYPE, StreamTypeNames.RAW_EVENTS);

        final String feedName2 = FileSystemTestUtil.getUniqueTestString();
        final AttributeMap attributeMap2 = new AttributeMap();
        attributeMap2.put(StandardHeaderArguments.FEED, feedName2);
        attributeMap2.put(StandardHeaderArguments.TYPE, StreamTypeNames.RAW_EVENTS);

        streamTargetStreamHandlers.handle(attributeMap1, handler -> {
            try {
                handler.addEntry("1" + StroomZipFileType.META.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("1" + StroomZipFileType.CONTEXT.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("1" + StroomZipFileType.DATA.getExtension(), new ByteArrayInputStream(new byte[0]));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        streamTargetStreamHandlers.handle(attributeMap2, handler -> {
            try {
                handler.addEntry("2" + StroomZipFileType.META.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.CONTEXT.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.DATA.getExtension(), new ByteArrayInputStream(new byte[0]));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(streamStore.getStreamStoreCount()).isEqualTo(2);
    }

    /**
     * This test is used to check that streams are aggregated if the feed is not
     * reference.
     *
     * @throws IOException
     */
    @Test
    void testFeedAggregation() {
        streamStore.clear();

        final String feedName = FileSystemTestUtil.getUniqueTestString();
        final AttributeMap attributeMap = new AttributeMap();
        attributeMap.put(StandardHeaderArguments.FEED, feedName);
        attributeMap.put(StandardHeaderArguments.TYPE, StreamTypeNames.RAW_EVENTS);

        streamTargetStreamHandlers.handle(attributeMap, handler -> {
            try {
                handler.addEntry("1" + StroomZipFileType.META.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("1" + StroomZipFileType.CONTEXT.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("1" + StroomZipFileType.DATA.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.META.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.CONTEXT.getExtension(), new ByteArrayInputStream(new byte[0]));
                handler.addEntry("2" + StroomZipFileType.DATA.getExtension(), new ByteArrayInputStream(new byte[0]));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(streamStore.getStreamStoreCount()).isEqualTo(1);
    }
}