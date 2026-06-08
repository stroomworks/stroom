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

package stroom.sqlstore.impl.pipeline;

import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.pipeline.state.MetaHolder;
import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.impl.UpdatableTemporalStoreProvider;
import stroom.sqlstore.shared.UnknownStoreException;
import stroom.svg.shared.SvgImage;
import stroom.util.date.DateUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;
import stroom.util.shared.TemporalEntry;
import stroom.util.xml.XMLUtil;

import jakarta.inject.Inject;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

@ConfigurableElement(
        type = "SqlStoreFilter",
        displayValue = "SQL Store Filter",
        description = "Takes XML input (conforming to the reference-data:2 schema) and " +
                "loads the data into the SQL Updatable Temporal Store.",
        category = Category.FILTER,
        roles = {
                PipelineElementType.ROLE_TARGET,
                PipelineElementType.ROLE_HAS_TARGETS},
        icon = SvgImage.DATABASE)
public class SqlStoreFilter extends AbstractXMLFilter {
    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(SqlStoreFilter.class);

    private final ErrorReceiverProxy errorReceiverProxy;
    private final LocationFactoryProxy locationFactory;
    private final MetaHolder metaHolder;
    private final UpdatableTemporalStoreProvider storeProvider;

    private Locator locator;
    private Instant streamEffectiveTime;

    private boolean insideMap = false;
    private boolean insideKey = false;
    private boolean insideTime = false;
    private boolean insideValue = false;
    private boolean insideFrom = false;
    private boolean insideTo = false;
    private boolean haveSeenXmlInValueElement = false;

    private final StringBuilder mapBuffer = new StringBuilder();
    private final StringBuilder keyBuffer = new StringBuilder();
    private final StringBuilder timeBuffer = new StringBuilder();
    private final StringBuilder fromBuffer = new StringBuilder();
    private final StringBuilder toBuffer = new StringBuilder();
    private final StringBuilder characterBuffer = new StringBuilder();

    private String mapName;
    private String key;
    private String timeString;
    private String fromString;
    private String toString;
    private String currentValue;

    private StringWriter stringWriter;
    private TransformerHandler transformerHandler;

    @Inject
    public SqlStoreFilter(final ErrorReceiverProxy errorReceiverProxy,
                          final LocationFactoryProxy locationFactory,
                          final MetaHolder metaHolder,
                          final UpdatableTemporalStoreProvider storeProvider) {
        this.errorReceiverProxy = errorReceiverProxy;
        this.locationFactory = locationFactory;
        this.metaHolder = metaHolder;
        this.storeProvider = storeProvider;
    }

    @Override
    public void startProcessing() {
        LOGGER.info("SqlStoreFilter.startProcessing()");
        try {
            final long ms = Optional
                    .ofNullable(metaHolder.getMeta().getEffectiveMs())
                    .orElse(metaHolder.getMeta().getCreateMs());
            streamEffectiveTime = Instant.ofEpochMilli(ms);
        } finally {
            super.startProcessing();
        }
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
        LOGGER.info("SqlStoreFilter.setDocumentLocator()");
        this.locator = locator;
        super.setDocumentLocator(locator);
    }

    @Override
    public void startElement(final String uri,
                             final String localName,
                             final String qName,
                             final Attributes atts) throws SAXException {
        LOGGER.info("SqlStoreFilter.startElement({}, {}, {}, {})", uri, localName, qName, atts);
        if (insideValue) {
            if (!haveSeenXmlInValueElement) {
                haveSeenXmlInValueElement = true;
                initTransformerHandler();
                if (!characterBuffer.isEmpty()) {
                    final char[] chars = characterBuffer.toString().toCharArray();
                    if (transformerHandler != null) {
                        transformerHandler.characters(chars, 0, chars.length);
                    }
                }
            }
            if (transformerHandler != null) {
                transformerHandler.startElement(uri, localName, qName, atts);
            }
        } else {
            if (localName.equalsIgnoreCase("state")
                    || localName.equalsIgnoreCase("range-state")
                    || localName.equalsIgnoreCase("temporal-range-state")
                    || localName.equalsIgnoreCase("session")
                    || localName.equalsIgnoreCase("histogram")
                    || localName.equalsIgnoreCase("metric")) {
                error("SQL Store Filter can only process '<temporal-state>' "
                        + "elements from the plan-b schema. Element '<" + localName + ">' is not supported.");
            }

            if (localName.equalsIgnoreCase("map")) {
                insideMap = true;
                mapBuffer.setLength(0);
            } else if (localName.equalsIgnoreCase("key")) {
                insideKey = true;
                keyBuffer.setLength(0);
            } else if (localName.equalsIgnoreCase("from")) {
                insideFrom = true;
                fromBuffer.setLength(0);
            } else if (localName.equalsIgnoreCase("to")) {
                insideTo = true;
                toBuffer.setLength(0);
            } else if (localName.equalsIgnoreCase("time") || localName.equalsIgnoreCase("effectiveTime")) {
                insideTime = true;
                timeBuffer.setLength(0);
            } else if (localName.equalsIgnoreCase("value")) {
                insideValue = true;
                currentValue = null;
                haveSeenXmlInValueElement = false;
                characterBuffer.setLength(0);
            }
        }
        super.startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(final String uri,
                           final String localName,
                           final String qName) throws SAXException {
        LOGGER.info("SqlStoreFilter.endElement({}, {}, {})", uri, localName, qName);
        if (insideValue && !localName.equalsIgnoreCase("value")) {
            if (transformerHandler != null) {
                transformerHandler.endElement(uri, localName, qName);
            }
        } else {
            if (localName.equalsIgnoreCase("map")) {
                insideMap = false;
                mapName = mapBuffer.toString().trim();
            } else if (localName.equalsIgnoreCase("key")) {
                insideKey = false;
                key = keyBuffer.toString().trim();
            } else if (localName.equalsIgnoreCase("from")) {
                insideFrom = false;
                fromString = fromBuffer.toString().trim();
            } else if (localName.equalsIgnoreCase("to")) {
                insideTo = false;
                toString = toBuffer.toString().trim();
            } else if (localName.equalsIgnoreCase("time") || localName.equalsIgnoreCase("effectiveTime")) {
                insideTime = false;
                timeString = timeBuffer.toString().trim();
            } else if (localName.equalsIgnoreCase("value")) {
                if (haveSeenXmlInValueElement) {
                    completeTransformerHandler();
                } else {
                    currentValue = characterBuffer.toString();
                }
                insideValue = false;
            } else if (localName.equalsIgnoreCase("reference")
                    || localName.equalsIgnoreCase("temporal-state")) {
                addReference();
                resetReference();
            } else if (localName.equalsIgnoreCase("state")
                    || localName.equalsIgnoreCase("range-state")
                    || localName.equalsIgnoreCase("temporal-range-state")
                    || localName.equalsIgnoreCase("session")
                    || localName.equalsIgnoreCase("histogram")
                    || localName.equalsIgnoreCase("metric")) {
                resetReference();
            }
        }
        super.endElement(uri, localName, qName);
    }

    @Override
    public void characters(final char[] ch,
                           final int start,
                           final int length) throws SAXException {
        LOGGER.info("SqlStoreFilter.characters()");
        if (insideValue) {
            if (haveSeenXmlInValueElement) {
                if (transformerHandler != null) {
                    transformerHandler.characters(ch, start, length);
                }
            } else {
                characterBuffer.append(ch, start, length);
            }
        } else {
            if (insideMap) {
                mapBuffer.append(ch, start, length);
            } else if (insideKey) {
                keyBuffer.append(ch, start, length);
            } else if (insideTime) {
                timeBuffer.append(ch, start, length);
            } else if (insideFrom) {
                fromBuffer.append(ch, start, length);
            } else if (insideTo) {
                toBuffer.append(ch, start, length);
            }
        }
        super.characters(ch, start, length);
    }

    @Override
    public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
        LOGGER.info("SqlStoreFilter.startPrefixMapping()");
        if (insideValue && transformerHandler != null) {
            transformerHandler.startPrefixMapping(prefix, uri);
        }
        super.startPrefixMapping(prefix, uri);
    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        LOGGER.info("SqlStoreFilter.endPrefixMapping()");
        if (insideValue && transformerHandler != null) {
            transformerHandler.endPrefixMapping(prefix);
        }
        super.endPrefixMapping(prefix);
    }

    private void initTransformerHandler() {
        LOGGER.info("SqlStoreFilter.initTransformerHandler()");
        try {
            stringWriter = new StringWriter();
            transformerHandler = XMLUtil.createTransformerHandler(false);
            transformerHandler.setResult(new StreamResult(stringWriter));
            transformerHandler.startDocument();
        } catch (final Exception e) {
            error("Failed to initialize XML transformer for value element", e);
        }
    }

    private void completeTransformerHandler() {
        LOGGER.info("SqlStoreFilter.completeTransformerHandler()");
        try {
            if (transformerHandler != null) {
                transformerHandler.endDocument();
                currentValue = stringWriter.toString();
            }
        } catch (final Exception e) {
            error("Failed to complete XML serialization for value element", e);
        } finally {
            transformerHandler = null;
            stringWriter = null;
        }
    }

    private void addReference() {
        LOGGER.info("SqlStoreFilter.addReference()");
        if (NullSafe.isEmptyString(mapName)) {
            error("Map name is missing for reference entry.");
            return;
        }
        if (NullSafe.isEmptyString(key)) {
            if (!NullSafe.isEmptyString(fromString) && !NullSafe.isEmptyString(toString)) {
                key = fromString + " - " + toString;
            }
        }
        if (NullSafe.isEmptyString(key)) {
            error("Key is missing for reference entry in map: " + mapName);
            return;
        }

        try {
            final UpdatableTemporalStore store = storeProvider.get(mapName);

            Instant entryTime = streamEffectiveTime;
            if (!NullSafe.isEmptyString(timeString)) {
                try {
                    entryTime = DateUtil.parseNormalDateTimeStringToInstant(timeString);
                } catch (final RuntimeException e) {
                    error("Unable to parse string \"" + timeString
                            + "\" as datetime for temporal entry in map: " + mapName, e);
                    return;
                }
            }

            store.create(new TemporalEntry(mapName, key, entryTime.toEpochMilli(), currentValue));

        } catch (final UnknownStoreException e) {
            error("Unknown SQL store map '" + mapName + "'. "
                    + "Please ensure a SqlTemporalStoreDoc has been created and configured with this "
                    + "map name under the explorer tree.");
        } catch (final RuntimeException e) {
            error("Error writing reference entry to SQL store: " + e.getMessage(), e);
        }
    }

    private void resetReference() {
        LOGGER.info("SqlStoreFilter.resetReference()");
        mapName = null;
        key = null;
        timeString = null;
        fromString = null;
        toString = null;
        currentValue = null;
        characterBuffer.setLength(0);
        haveSeenXmlInValueElement = false;
    }

    private void error(final String message) {
        LOGGER.info("SqlStoreFilter.error({})", message);
        errorReceiverProxy.log(Severity.ERROR, locationFactory.create(locator), getElementId(), message, null);
    }

    private void error(final String message, final Throwable e) {
        LOGGER.info("SqlStoreFilter.error({}, {})", message, e.getMessage(), e);
        errorReceiverProxy.log(Severity.ERROR, locationFactory.create(locator), getElementId(), message, e);
    }
}
