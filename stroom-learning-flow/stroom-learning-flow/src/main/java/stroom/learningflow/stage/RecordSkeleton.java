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

package stroom.learningflow.stage;

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

/// Computes a record's skeleton, its structure with its values removed, which is used to tell one kind of
/// record from another when a sample is chosen, records are counted and a target is checked.
///
/// For raw text it is the token-class skeleton of the first non-blank line: runs of digits, letters and
/// whitespace are each reduced to one class character, and punctuation is kept, so two lines that differ
/// only in their values share a skeleton. For markup it is the element tree of one record, with its
/// attribute names and the attribute values that name a field, but no text.
public final class RecordSkeleton {

    private RecordSkeleton() {
    }

    /// Markup begins with a tag, a declaration or a comment. A BSD syslog line begins with e.g. `<34>`, which
    /// is text, so a digit after `<` does not count.
    private static final Pattern MARKUP = Pattern.compile("^\\s*<(\\?|!|[A-Za-z_])");
    private static final char BOM = '\uFEFF';
    /// The attribute that names a value in the XML form of JSON produced by the JSON parser; its value is
    /// always a field's name.
    private static final String KEY = "key";
    /// Attributes that name a field by convention, as in `<Data Name="LogonType">`, but just as often hold a
    /// value, as in `<User Name="alice"/>`. Their value is only included where the element repeats among its
    /// siblings, which is what a run of named fields looks like and a lone value does not.
    private static final Set<String> NAMING = Set.of("name", "Name", "Key");

    public static boolean isMarkup(final String data) {
        return MARKUP.matcher(withoutBom(data)).find();
    }

    /// Returns the text without a leading byte order mark, which is not content and would otherwise make
    /// markup or JSON look like plain text. The stage strips it from a stream once, and the tests that
    /// classify text call this.
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

    /// Gets the skeleton of one markup record, which identifies its kind so that one representative of each
    /// kind can be chosen. It holds the elements and their attribute names, nested as in the record, with no
    /// text. It also holds the value of a `key` attribute, and of a `Name` attribute where the element
    /// repeats among its siblings, because records of one vocabulary (named `Data` elements, or a JSON map)
    /// are told apart by what their fields are called. Repeated siblings with the same skeleton count once,
    /// so an array is one kind however many items it holds. A record that does not parse as XML gets its
    /// text skeleton instead.
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

    /// One element of a record: its name, its attributes in document order, and its children. When it is
    /// rendered, only the attribute values that name a field are included, and repeated identical children
    /// appear once.
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
