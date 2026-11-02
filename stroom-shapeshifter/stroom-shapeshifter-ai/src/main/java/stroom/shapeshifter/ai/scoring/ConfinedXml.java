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

package stroom.shapeshifter.ai.scoring;

import stroom.util.xml.SAXParserFactoryFactory;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.StringReader;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.sax.SAXSource;

/**
 * A parser for XML a model wrote or a model's stylesheet produced (design 01 §11): a DOCTYPE is refused
 * outright, so no entity — external or expanding — can be declared, and nothing in the text can make the
 * harness fetch anything. Saxon's own parse options are not consulted for a {@code StreamSource}, which
 * is why the reader is supplied to it rather than configured on it.
 */
public final class ConfinedXml {

    private ConfinedXml() {
    }

    public static XMLReader reader() {
        try {
            final XMLReader reader = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
            reader.setFeature(SAXParserFactoryFactory.FEATURE_DISALLOW_DOCTYPE, true);
            reader.setFeature(SAXParserFactoryFactory.FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
            reader.setFeature(SAXParserFactoryFactory.FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
            reader.setFeature(SAXParserFactoryFactory.FEATURE_LOAD_EXTERNAL_DTD, false);
            return reader;
        } catch (final SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Unable to set up a confined XML parser", e);
        }
    }

    public static SAXSource source(final String xml) {
        return new SAXSource(reader(), new InputSource(new StringReader(xml)));
    }
}
