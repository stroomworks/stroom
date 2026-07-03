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

package stroom.floormap.client;

import com.google.gwt.xml.client.Document;
import com.google.gwt.xml.client.Element;
import com.google.gwt.xml.client.Node;
import com.google.gwt.xml.client.NodeList;
import com.google.gwt.xml.client.XMLParser;

/**
 * {@link ValueAccessor} implementation for XML values.
 *
 * <p>Paths use XPath-from-root syntax (e.g. {@code "/entry/type"}).
 * Attributes are accessed via {@code @} notation
 * (e.g. {@code "/entry/@type"}).</p>
 *
 * <h3>Namespace support</h3>
 * <p>This implementation does <b>not</b> support XML namespaces.
 * Paths match elements by local name only. This is intentional:
 * floor map values are user-defined and authored by Stroom, making
 * namespace-qualified XML unlikely in practice.</p>
 *
 * <p>If namespace support is needed in future, the extension path
 * is:</p>
 * <ol>
 *   <li>Add a namespace-prefix-to-URI mapping to the floor map
 *       settings (e.g. {@code ns} → {@code http://example.com/schema})</li>
 *   <li>Support namespace-aware path syntax
 *       (e.g. {@code /ns:entry/ns:type})</li>
 *   <li>Use {@code getElementsByTagNameNS()} /
 *       {@code getAttributeNS()} instead of the non-namespace-aware
 *       equivalents used here</li>
 *   <li>Emit {@code xmlns} declarations when serialising</li>
 * </ol>
 * <p>GWT's {@code com.google.gwt.xml.client} API already provides
 * {@code Node.getNamespaceURI()}, {@code getPrefix()}, and
 * {@code getLocalName()}, so no architectural changes to the
 * {@link ValueAccessor} interface are required.</p>
 *
 * <h3>Numeric arrays</h3>
 * <p>Numeric arrays (coordinates, transformation matrices) are stored
 * as comma-separated text content
 * (e.g. {@code <coords>500.0,500.0</coords>}).</p>
 */
public final class XmlValueAccessor implements ValueAccessor {

    static final XmlValueAccessor INSTANCE = new XmlValueAccessor();

    private XmlValueAccessor() {
        // Singleton
    }

    @Override
    public ParsedValue parse(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            final Document doc = XMLParser.parse(raw);
            return doc != null ? new ParsedValue(doc) : null;
        } catch (final Exception e) {
            return null;
        }
    }

    @Override
    public ParsedValue createEmpty(final String rootName) {
        final String name = rootName != null && !rootName.isEmpty()
                ? rootName : "entry";
        final Document doc = XMLParser.parse(
                "<" + name + "></" + name + ">");
        return new ParsedValue(doc);
    }

    @Override
    public String getString(final ParsedValue value,
                            final String path) {
        final Document doc = asDoc(value);
        if (doc == null || path == null) {
            return null;
        }
        final PathTarget target = resolvePath(doc, path);
        if (target == null) {
            return null;
        }
        if (target.isAttribute) {
            return target.parent.getAttribute(target.localName);
        }
        final Element elem = findChildElement(
                target.parent, target.localName);
        return elem != null ? getTextContent(elem) : null;
    }

    @Override
    public void setString(final ParsedValue value, final String path,
                          final String textValue) {
        final Document doc = asDoc(value);
        if (doc == null || path == null) {
            return;
        }
        final PathTarget target = resolveOrCreatePath(doc, path);
        if (target == null) {
            return;
        }
        if (target.isAttribute) {
            if (textValue != null) {
                target.parent.setAttribute(target.localName,
                        textValue);
            } else {
                target.parent.removeAttribute(target.localName);
            }
        } else {
            Element elem = findChildElement(
                    target.parent, target.localName);
            if (textValue != null) {
                if (elem == null) {
                    elem = doc.createElement(target.localName);
                    target.parent.appendChild(elem);
                }
                setTextContent(elem, textValue);
            } else if (elem != null) {
                target.parent.removeChild(elem);
            }
        }
    }

    @Override
    public double[] getArray(final ParsedValue value,
                             final String path) {
        final String text = getString(value, path);
        if (text == null || text.isEmpty()) {
            return null;
        }
        return parseCommaSeparated(text);
    }

    @Override
    public void setArray(final ParsedValue value, final String path,
                         final double[] numbers) {
        if (numbers == null) {
            setString(value, path, null);
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(numbers[i]);
        }
        setString(value, path, sb.toString());
    }

    @Override
    public String serialize(final ParsedValue value) {
        final Document doc = asDoc(value);
        if (doc == null) {
            return null;
        }
        final Element root = doc.getDocumentElement();
        if (root == null) {
            return null;
        }
        return serializeElement(root);
    }

    @Override
    public boolean canParse(final String raw) {
        return raw != null && raw.trim().startsWith("<");
    }

    // ---- Internal helpers ----

    /**
     * Extracts the {@link Document} from a {@link ParsedValue}.
     */
    private static Document asDoc(final ParsedValue value) {
        if (value == null) {
            return null;
        }
        final Object backing = value.getBacking();
        return backing instanceof Document ? (Document) backing : null;
    }

    /**
     * Resolves a path like {@code "/entry/type"} or
     * {@code "/entry/@type"} to a parent element and a local
     * name. Returns {@code null} if any intermediate element is
     * missing.
     */
    private static PathTarget resolvePath(final Document doc,
                                          final String path) {
        final String[] segments = splitPath(path);
        if (segments.length == 0) {
            return null;
        }

        // Navigate to the parent of the final segment.
        Element current = doc.getDocumentElement();
        if (current == null) {
            return null;
        }

        // First segment must match the root element name.
        if (!current.getTagName().equals(segments[0])) {
            return null;
        }

        for (int i = 1; i < segments.length - 1; i++) {
            final Element child = findChildElement(
                    current, segments[i]);
            if (child == null) {
                return null;
            }
            current = child;
        }

        final String last = segments[segments.length - 1];
        if (last.startsWith("@")) {
            return new PathTarget(
                    current, last.substring(1), true);
        }
        return new PathTarget(current, last, false);
    }

    /**
     * Like {@link #resolvePath}, but creates intermediate elements
     * if they don't exist.
     */
    private static PathTarget resolveOrCreatePath(
            final Document doc, final String path) {
        final String[] segments = splitPath(path);
        if (segments.length == 0) {
            return null;
        }

        Element current = doc.getDocumentElement();
        if (current == null) {
            return null;
        }

        // First segment must match the root element name.
        if (!current.getTagName().equals(segments[0])) {
            return null;
        }

        for (int i = 1; i < segments.length - 1; i++) {
            Element child = findChildElement(
                    current, segments[i]);
            if (child == null) {
                child = doc.createElement(segments[i]);
                current.appendChild(child);
            }
            current = child;
        }

        final String last = segments[segments.length - 1];
        if (last.startsWith("@")) {
            return new PathTarget(
                    current, last.substring(1), true);
        }
        return new PathTarget(current, last, false);
    }

    /**
     * Splits a path like {@code "/entry/type"} into
     * {@code ["entry", "type"]}. Leading slash is stripped.
     */
    private static String[] splitPath(final String path) {
        String p = path;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return new String[0];
        }
        return p.split("/");
    }

    /**
     * Finds the first child element with the given tag name.
     */
    private static Element findChildElement(
            final Element parent, final String tagName) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                final Element elem = (Element) child;
                if (elem.getTagName().equals(tagName)) {
                    return elem;
                }
            }
        }
        return null;
    }

    /**
     * Gets the concatenated text content of an element's direct
     * text nodes.
     */
    private static String getTextContent(final Element elem) {
        final StringBuilder sb = new StringBuilder();
        final NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                sb.append(child.getNodeValue());
            }
        }
        final String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Sets the text content of an element, replacing any existing
     * child nodes.
     */
    private static void setTextContent(final Element elem,
                                       final String text) {
        // Remove existing children.
        while (elem.hasChildNodes()) {
            elem.removeChild(elem.getFirstChild());
        }
        if (text != null) {
            elem.appendChild(
                    elem.getOwnerDocument().createTextNode(text));
        }
    }

    /**
     * Parses a comma-separated string of numbers.
     */
    private static double[] parseCommaSeparated(final String text) {
        final String[] parts = text.split(",");
        final double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Double.parseDouble(parts[i].trim());
            } catch (final NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    /**
     * Serialises an element and its children to an XML string.
     * GWT does not provide a built-in DOM serialiser, so this is
     * a simple recursive implementation.
     */
    private static String serializeElement(final Element elem) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<").append(elem.getTagName());

        // Attributes — GWT's Element doesn't expose an attribute
        // iterator directly, but getAttributes() returns a NamedNodeMap.
        final com.google.gwt.xml.client.NamedNodeMap attrs =
                elem.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                final Node attr = attrs.item(i);
                sb.append(" ").append(attr.getNodeName())
                        .append("=\"")
                        .append(escapeXml(attr.getNodeValue()))
                        .append("\"");
            }
        }

        final NodeList children = elem.getChildNodes();
        if (children.getLength() == 0) {
            sb.append("/>");
        } else {
            sb.append(">");
            for (int i = 0; i < children.getLength(); i++) {
                final Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    sb.append(serializeElement((Element) child));
                } else if (child.getNodeType() == Node.TEXT_NODE) {
                    sb.append(escapeXml(child.getNodeValue()));
                }
            }
            sb.append("</").append(elem.getTagName()).append(">");
        }
        return sb.toString();
    }

    /**
     * Escapes XML special characters.
     */
    private static String escapeXml(final String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Holds the result of resolving a path: the parent element,
     * the local name of the target, and whether it's an attribute.
     */
    private static final class PathTarget {
        final Element parent;
        final String localName;
        final boolean isAttribute;

        PathTarget(final Element parent, final String localName,
                   final boolean isAttribute) {
            this.parent = parent;
            this.localName = localName;
            this.isAttribute = isAttribute;
        }
    }
}
