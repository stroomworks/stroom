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

package stroom.processor.impl;

import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.parser.AbstractParser;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.pipeline.state.MetaHolder;
import stroom.processor.api.DeferTaskException;
import stroom.svg.shared.SvgImage;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.xml.SAXParserFactoryFactory;

import jakarta.inject.Inject;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/// A parser that stands where Shapeshifter AI's will: the first time it meets a stream it asks for the
/// task back later, and the next time it emits one small document. What it proves is the path the
/// exception takes out of a pipeline, not anything about parsing.
@ConfigurableElement(
        type = DeferringParser.TYPE,
        category = Category.PARSER,
        description = "Test only: defers the task the first time it sees each stream, then emits a document.",
        roles = {
                PipelineElementType.ROLE_PARSER,
                PipelineElementType.ROLE_HAS_TARGETS},
        icon = SvgImage.PIPELINE_XML)
public class DeferringParser extends AbstractParser {

    public static final String TYPE = "TestDeferringParser";
    public static final String REASON = "The translation for this stream is still being learned";
    public static final String EMITTED_ELEMENT = "emitted";

    /// The streams this JVM has already deferred once, across tasks and across pipeline scopes.
    private static final Set<Long> DEFERRED_ONCE = ConcurrentHashMap.newKeySet();
    private static final SAXParserFactory PARSER_FACTORY = SAXParserFactoryFactory.newInstance();
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DeferringParser.class);

    private final MetaHolder metaHolder;

    @Inject
    public DeferringParser(final ErrorReceiverProxy errorReceiverProxy,
                           final LocationFactoryProxy locationFactory,
                           final MetaHolder metaHolder) {
        super(errorReceiverProxy, locationFactory);
        this.metaHolder = metaHolder;
    }

    /// Forget every stream, so that a test starts from the first encounter.
    public static void reset() {
        DEFERRED_ONCE.clear();
    }

    /// A real SAX reader over a fixed document, so that what the writer below sees is exactly what it would
    /// see from `XMLParser` — with the one difference that the first sight of a stream is a deferral.
    @Override
    protected XMLReader createReader() throws SAXException {
        final XMLReader real;
        try {
            real = PARSER_FACTORY.newSAXParser().getXMLReader();
        } catch (final ParserConfigurationException e) {
            throw ProcessException.wrap(e);
        }
        return new stroom.pipeline.xml.converter.AbstractParser() {
            @Override
            public void parse(final InputSource input) throws IOException, SAXException {
                final long metaId = metaHolder.getMeta().getId();
                if (DEFERRED_ONCE.add(metaId)) {
                    LOGGER.info("Deferring stream {}", metaId);
                    throw new DeferTaskException(REASON);
                }
                LOGGER.info("Emitting for stream {}", metaId);
                real.setContentHandler(getContentHandler());
                real.setErrorHandler(getErrorHandler());
                // One record under a root: the XML writer writes a record when its element closes at depth
                // one, so a root alone would write nothing.
                real.parse(new InputSource(new StringReader(
                        "<records><" + EMITTED_ELEMENT + "/></records>")));
            }
        };
    }
}
