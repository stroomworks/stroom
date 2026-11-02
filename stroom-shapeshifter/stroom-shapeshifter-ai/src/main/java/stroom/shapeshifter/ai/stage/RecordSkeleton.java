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

package stroom.shapeshifter.ai.stage;

import stroom.util.xml.SAXParserFactoryFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;

/**
 * A record's structure with its values removed: what tells one kind of record from another where a
 * sample is chosen, records are counted and a target is checked. For raw text it is the token-class
 * skeleton of the first non-blank line — runs of digits, letters, whitespace and each punctuation
 * character reduced to a class — so two lines differing only in values share it. For markup it is the
 * element tree of one record, its attribute names and the values that name a field, no text.
 */
public final class RecordSkeleton {

    private RecordSkeleton() {
    }

    /**
     * Markup begins with a tag, a declaration or a comment; a BSD syslog line begins with {@code <34>},
     * which is text.
     */
    private static final Pattern MARKUP = Pattern.compile("^\\s*<(\\?|!|[A-Za-z_])");
    private static final char BOM = '\uFEFF';
    /**
     * The attribute that names a value in the XSL/json vocabulary: always a field's name, by construction.
     */
    private static final String KEY = "key";
    /**
     * Attributes that name a field by convention — {@code <Data Name="LogonType">} — but as often carry a
     * value — {@code <User Name="alice"/>}: their value counts only where the element repeats among its
     * siblings, which is what a run of named fields looks like and a lone value does not.
     */
    private static final Set<String> NAMING = Set.of("name", "Name", "Key");

    public static boolean isMarkup(final String data) {
        return MARKUP.matcher(withoutBom(data)).find();
    }

    /**
     * The text without a leading byte order mark, which is not content and would otherwise make markup or
     * JSON read as text; the stage strips it from a stream once, and the tests that classify text call this.
     */
    public static String withoutBom(final String data) {
        return !data.isEmpty() && data.charAt(0) == BOM
                ? data.substring(1)
                : data;
    }

    public static String textSkeleton(final String data) {
        final String line = data.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        final StringBuilder skeleton = new StringBuilder();
        char previous = 0;
        for (final char c : line.toCharArray()) {
            final char cls = Character.isDigit(c)
                    ? '9'
                    : Character.isLetter(c)
                            ? 'a'
                            : Character.isWhitespace(c)
                                    ? ' '
                                    : c;
            if (cls != previous || !(cls == '9' || cls == 'a' || cls == ' ')) {
                skeleton.append(cls);
            }
            previous = cls;
        }
        return skeleton.toString();
    }

    /**
     * The skeleton of one markup record — its kind, for choosing one representative of each: the elements
     * and their attribute names, nested as the record nests them, no text; the value of a {@code key}
     * attribute, and of a {@code Name} where the element repeats among its siblings, since records of one
     * vocabulary — named {@code Data}, or a JSON map — are told apart by what their fields are called.
     * Repeated siblings of one skeleton count once, so an array is one kind however many items it holds.
     * A record that does not parse has its text skeleton.
     */
    public static String recordSkeleton(final String record) {
        final Node root = tree(record);
        return root == null
                ? textSkeleton(record)
                : root.render();
    }

    private static Node tree(final String record) {
        final Deque<Node> open = new ArrayDeque<>();
        final Node[] root = new Node[1];
        final DefaultHandler handler = new DefaultHandler() {
            @Override
            public void startElement(final String uri,
                                     final String localName,
                                     final String qName,
                                     final Attributes attributes) {
                final Node node = new Node(localName.isEmpty()
                        ? qName
                        : localName, attributes);
                if (open.isEmpty()) {
                    root[0] = node;
                } else {
                    open.peek().children.add(node);
                }
                open.push(node);
            }

            @Override
            public void endElement(final String uri, final String localName, final String qName) {
                open.pop();
            }
        };
        try {
            final XMLReader reader = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
            reader.setContentHandler(handler);
            reader.parse(new InputSource(new StringReader(record)));
        } catch (final SAXException | IOException | ParserConfigurationException e) {
            return null;
        }
        return root[0];
    }

    /**
     * One element of a record: its name, its attribute names in document order with the values that name,
     * and its distinct children.
     */
    private static final class Node {

        private final String name;
        private final List<String> attributeNames = new ArrayList<>();
        private final List<String> attributeValues = new ArrayList<>();
        private final List<Node> children = new ArrayList<>();

        private Node(final String name, final Attributes attributes) {
            this.name = name;
            for (int i = 0; i < attributes.getLength(); i++) {
                final String local = attributes.getLocalName(i);
                attributeNames.add(local.isEmpty()
                        ? attributes.getQName(i)
                        : local);
                attributeValues.add(attributes.getValue(i));
            }
        }

        private String render() {
            return render(false);
        }

        private String render(final boolean repeated) {
            final StringBuilder out = new StringBuilder("<").append(name);
            for (int i = 0; i < attributeNames.size(); i++) {
                final String attribute = attributeNames.get(i);
                out.append(' ').append(attribute);
                if (KEY.equals(attribute) || repeated && NAMING.contains(attribute)) {
                    out.append('=').append(attributeValues.get(i));
                }
            }
            out.append('>');
            final Map<String, Long> siblings = children.stream()
                    .collect(Collectors.groupingBy(child -> child.name, Collectors.counting()));
            final Set<String> distinct = new LinkedHashSet<>();
            for (final Node child : children) {
                distinct.add(child.render(siblings.get(child.name) > 1));
            }
            distinct.forEach(out::append);
            return out.append("</").append(name).append('>').toString();
        }
    }
}
