/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.ai.extraction;

import stroom.pipeline.DefaultLocationFactory;
import stroom.pipeline.errorhandler.ErrorHandlerAdaptor;
import stroom.pipeline.errorhandler.StoredErrorReceiver;
import stroom.pipeline.filter.MergeFilter;
import stroom.pipeline.xml.converter.ds3.DSLocator;
import stroom.shapeshifter.ai.extraction.Compilation.Compiled;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.Severity;
import stroom.util.shared.TextRange;
import stroom.util.xml.XMLUtil;

import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/**
 * Runs a compiled Data Splitter configuration over one input, headlessly, and captures everything the
 * scorers need. This is the extraction-stage replay unit of design §4: the whole input, re-run from the
 * top for every candidate.
 */
public final class DataSplitterRunner {

    private static final String RECORD_ELEMENT = "record";

    private DataSplitterRunner() {
    }

    public static ExtractionResult run(final Compiled compiled, final String input) {
        final StoredErrorReceiver errorReceiver = new StoredErrorReceiver();
        final StringWriter output = new StringWriter();
        final RecordRangeRecorder recorder = new RecordRangeRecorder();

        try {
            final TransformerHandler serialiser = XMLUtil.createTransformerHandler(true);
            serialiser.setResult(new StreamResult(output));
            final MergeFilter mergeFilter = new MergeFilter();
            mergeFilter.setContentHandler(serialiser);
            recorder.setContentHandler(mergeFilter);

            final XMLReader parser = compiled.parserFactory().getParser();
            parser.setContentHandler(recorder);
            // The Data Splitter casts its error handler to this type, so nothing else will do.
            parser.setErrorHandler(new ErrorHandlerAdaptor(
                    DataSplitterCompiler.ELEMENT_ID,
                    new DefaultLocationFactory(),
                    errorReceiver));

            mergeFilter.startProcessing();
            try {
                parser.parse(new InputSource(new StringReader(input)));
            } catch (final RuntimeException | SAXException | IOException e) {
                // The configuration is untrusted: a group reference with no group behind it, for
                // instance, escapes as an unchecked exception. That is a fault in the candidate and
                // scores as one, not a fault in the harness.
                errorReceiver.log(Severity.FATAL_ERROR, null, DataSplitterCompiler.ELEMENT_ID, e.getMessage(), e);
            } finally {
                mergeFilter.endProcessing();
            }
        } catch (final TransformerConfigurationException | SAXException e) {
            throw new IllegalStateException("Unable to set up the extraction run", e);
        }

        return new ExtractionResult(
                output.toString(),
                List.copyOf(recorder.ranges),
                List.copyOf(errorReceiver.getList()));
    }

    /**
     * Passes every event through unchanged, noting the input span of each record as it closes. The Data
     * Splitter publishes its own reader as the document locator, and that reader knows where the current
     * record began and where it is now.
     */
    private static final class RecordRangeRecorder extends XMLFilterImpl {

        private final List<TextRange> ranges = new ArrayList<>();
        private DSLocator locator;

        @Override
        public void setDocumentLocator(final Locator locator) {
            if (locator instanceof final DSLocator dsLocator) {
                this.locator = dsLocator;
            }
            super.setDocumentLocator(locator);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) throws SAXException {
            if (locator != null && RECORD_ELEMENT.equals(localName)) {
                final Locator start = locator.getRecordStartLocator();
                final Locator end = locator.getRecordEndLocator();
                ranges.add(new TextRange(
                        DefaultLocation.of(start.getLineNumber(), start.getColumnNumber()),
                        DefaultLocation.of(end.getLineNumber(), end.getColumnNumber())));
            }
            super.endElement(uri, localName, qName);
        }
    }
}
