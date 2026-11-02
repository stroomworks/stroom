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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * What a schema says an element may contain, read from the XSD text and written for a model: the
 * children in order, optional ones marked {@code ?}, repeating ones {@code *} or {@code +}, a choice as
 * {@code (A | B)}, and a required child that is itself complex opened one level so that a candidate can
 * finish the element in one step rather than be told one missing child per turn — the ladder the first
 * live run climbed (design 02 §6.2); a required child whose type is an enumeration carries its values as
 * {@code [A | B]}, so that a candidate is not told the element it added holds a value the schema does
 * not know. Resolves named and inline complex types, simple types, {@code ref}, {@code group} and
 * {@code extension}; anything it cannot follow it leaves out rather than guesses at.
 */
public final class ContentModels {

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final int TEXT_LIMIT = 700;
    private static final int PARENTS_SHOWN = 3;
    private static final int VALUES_SHOWN = 8;

    /**
     * Every element declaration by local name; a name declared in several places has several.
     */
    private final Map<String, List<Element>> elements = new HashMap<>();
    private final Map<String, Element> complexTypes = new HashMap<>();
    private final Map<String, Element> groups = new HashMap<>();
    /**
     * The values of every named simple type that is an enumeration.
     */
    private final Map<String, List<String>> enumerations = new HashMap<>();
    private final Set<String> targetNamespaces = new LinkedHashSet<>();

    /**
     * A schema text that does not parse is left out: the hint is best effort, and the validator has its
     * own opinion of the group's documents.
     */
    public ContentModels(final Collection<String> schemaTexts) {
        for (final String text : schemaTexts) {
            parse(text).ifPresent(document -> {
                final Element schema = document.getDocumentElement();
                if (schema.hasAttribute("targetNamespace")) {
                    targetNamespaces.add(schema.getAttribute("targetNamespace"));
                }
                index(schema);
            });
        }
    }

    /**
     * The namespace the group's elements live in, where the schemas agree on one.
     */
    public Optional<String> namespace() {
        return targetNamespaces.size() == 1
                ? Optional.of(targetNamespaces.iterator().next())
                : Optional.empty();
    }

    /**
     * The content model of every element declared with this name, each placed by the element that
     * declares it, so that "Door" is told apart where it means two things.
     */
    public List<String> describe(final String elementName) {
        final List<String> described = new ArrayList<>();
        for (final Element declaration : elements.getOrDefault(elementName, List.of())) {
            final List<Particle> particles = particles(declaration);
            if (particles.isEmpty()) {
                continue;
            }
            final String text = elementName + parentOf(declaration).map(parent -> " (in " + parent + ")").orElse("")
                                + " contains, in order: " + render(particles, true)
                                + alternativesTo(declaration).map(choice -> ". It is one alternative of " + choice
                                                                            + "; another may be simpler")
                                .orElse("");
            if (!described.contains(text)) {
                described.add(limit(text));
            }
        }
        return described;
    }

    /**
     * How the notation reads, said once alongside the first hint.
     */
    public static String legend() {
        return "? optional, * any number, + one or more, ( | ) one of, { } what a required child holds, "
               + "[ | ] the values allowed";
    }

    /**
     * Where the element is one option of a choice in its parent, that choice — so that a model told what a
     * costly element needs can also see what it could have used instead.
     */
    private Optional<String> alternativesTo(final Element declaration) {
        for (Node node = declaration.getParentNode(); node != null; node = node.getParentNode()) {
            if (!(node instanceof final Element element) || !XS.equals(element.getNamespaceURI())) {
                return Optional.empty();
            }
            if ("choice".equals(element.getLocalName())) {
                final List<Particle> options = new ArrayList<>();
                collect(wrapChoice(element), options, 1);
                return options.isEmpty()
                        ? Optional.empty()
                        : Optional.of(options.get(0).render(false));
            }
            if ("element".equals(element.getLocalName()) || "complexType".equals(element.getLocalName())) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Element wrapChoice(final Element choice) {
        final Element holder = choice.getOwnerDocument().createElementNS(XS, "xs:sequence");
        holder.appendChild(choice.cloneNode(true));
        return holder;
    }

    /**
     * The content models of the elements that could hold both the child that appeared and the children
     * the validator expected instead — the parents a message like <i>invalid content starting with
     * 'Description'; one of {Type} expected</i> does not name.
     */
    public List<String> describeParentsOf(final String child, final Collection<String> expected) {
        final Set<String> withChild = new LinkedHashSet<>(expected);
        withChild.add(child);
        final Candidates holdingChild = parentsHolding(withChild, expected);
        // A parent that requires what the validator expected is the likeliest; one that holds the child
        // that appeared as well is likelier still. A child the model invented is in no parent, and the
        // expected names alone still say where it stood.
        final Candidates holdingExpected = parentsHolding(new LinkedHashSet<>(expected), expected);
        final List<String> described = !holdingChild.required().isEmpty()
                ? holdingChild.required()
                : !holdingExpected.required().isEmpty()
                        ? holdingExpected.required()
                        : !holdingChild.any().isEmpty()
                                ? holdingChild.any()
                                : holdingExpected.any();
        return described.size() > PARENTS_SHOWN
                ? described.subList(0, PARENTS_SHOWN)
                : described;
    }

    private Candidates parentsHolding(final Set<String> wanted, final Collection<String> expected) {
        final List<String> required = new ArrayList<>();
        final List<String> any = new ArrayList<>();
        for (final List<Element> declarations : elements.values()) {
            for (final Element declaration : declarations) {
                final List<Particle> particles = particles(declaration);
                if (!names(particles).containsAll(wanted)) {
                    continue;
                }
                final String text = limit(declaration.getAttribute("name") + " contains, in order: "
                                          + render(particles, true));
                final boolean requiresExpected = names(particles.stream()
                        .filter(particle -> particle.minOccurs() > 0)
                        .toList()).containsAll(expected);
                final List<String> into = requiresExpected
                        ? required
                        : any;
                if (!into.contains(text)) {
                    into.add(text);
                }
            }
        }
        return new Candidates(required, any);
    }

    private record Candidates(List<String> required, List<String> any) {

    }

    private void index(final Element node) {
        final NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof final Element child && XS.equals(child.getNamespaceURI())) {
                switch (child.getLocalName()) {
                    case "element" -> {
                        if (child.hasAttribute("name")) {
                            elements.computeIfAbsent(child.getAttribute("name"), k -> new ArrayList<>()).add(child);
                        }
                    }
                    case "complexType" -> {
                        if (child.hasAttribute("name") && child.getParentNode() == node
                            && "schema".equals(node.getLocalName())) {
                            complexTypes.put(child.getAttribute("name"), child);
                        }
                    }
                    case "group" -> {
                        if (child.hasAttribute("name")) {
                            groups.put(child.getAttribute("name"), child);
                        }
                    }
                    case "simpleType" -> {
                        if (child.hasAttribute("name")) {
                            final List<String> values = enumerationOf(child);
                            if (!values.isEmpty()) {
                                enumerations.put(child.getAttribute("name"), values);
                            }
                        }
                    }
                    default -> {
                    }
                }
                index(child);
            }
        }
    }

    /**
     * The particles an element declaration holds: through its inline type or the named one it points at.
     */
    private List<Particle> particles(final Element declaration) {
        final Element type = declaration.hasAttribute("type")
                ? complexTypes.get(local(declaration.getAttribute("type")))
                : firstChild(declaration, "complexType");
        return type == null
                ? List.of()
                : particlesOfType(type, 0);
    }

    private List<Particle> particlesOfType(final Element complexType, final int depth) {
        final List<Particle> particles = new ArrayList<>();
        final Element complexContent = firstChild(complexType, "complexContent");
        final Element extension = complexContent == null
                ? null
                : firstChild(complexContent, "extension");
        if (extension != null) {
            final Element base = complexTypes.get(local(extension.getAttribute("base")));
            if (base != null && depth < 4) {
                particles.addAll(particlesOfType(base, depth + 1));
            }
            collect(extension, particles, depth);
        } else {
            collect(complexType, particles, depth);
        }
        return particles;
    }

    private void collect(final Element container, final List<Particle> into, final int depth) {
        final NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof final Element child) || !XS.equals(child.getNamespaceURI())) {
                continue;
            }
            switch (child.getLocalName()) {
                case "sequence", "all" -> collect(child, into, depth);
                case "choice" -> {
                    final List<Particle> options = new ArrayList<>();
                    final NodeList alternatives = child.getChildNodes();
                    for (int j = 0; j < alternatives.getLength(); j++) {
                        if (!(alternatives.item(j) instanceof final Element alternative)
                            || !XS.equals(alternative.getNamespaceURI())) {
                            continue;
                        }
                        if ("sequence".equals(alternative.getLocalName()) || "all".equals(alternative.getLocalName())) {
                            // One alternative that is itself a run of children stays a run, not more alternatives.
                            final List<Particle> run = new ArrayList<>();
                            collect(alternative, run, depth);
                            if (run.size() == 1) {
                                options.add(run.get(0));
                            } else if (!run.isEmpty()) {
                                options.add(new Particle(null, null, minOccurs(alternative), maxOccurs(alternative),
                                        null, run, List.of()));
                            }
                        } else {
                            collect(wrap(alternative), options, depth);
                        }
                    }
                    if (!options.isEmpty()) {
                        into.add(new Particle(null, options, minOccurs(child), maxOccurs(child), null, null,
                                List.of()));
                    }
                }
                case "group" -> {
                    final Element group = groups.get(local(child.getAttribute("ref")));
                    if (group != null && depth < 4) {
                        collect(group, into, depth + 1);
                    }
                }
                case "element" -> into.add(element(child, depth));
                case "sequence-alternative" -> collect(child, into, depth);
                default -> {
                }
            }
        }
    }

    private Particle element(final Element declaration, final int depth) {
        final String name = declaration.hasAttribute("ref")
                ? local(declaration.getAttribute("ref"))
                : declaration.getAttribute("name");
        // A required complex child is opened one level, so that finishing the parent does not stop at it.
        List<Particle> within = null;
        if (depth == 0 && minOccurs(declaration) > 0) {
            final Element type = declaration.hasAttribute("type")
                    ? complexTypes.get(local(declaration.getAttribute("type")))
                    : firstChild(declaration, "complexType");
            if (type != null) {
                within = particlesOfType(type, depth + 1);
            }
        }
        return new Particle(name, null, minOccurs(declaration), maxOccurs(declaration), within, null,
                valuesOf(declaration));
    }

    /**
     * The values a required element may hold where its type is an enumeration, named or inline.
     */
    private List<String> valuesOf(final Element declaration) {
        if (minOccurs(declaration) == 0) {
            return List.of();
        }
        if (declaration.hasAttribute("type")) {
            return enumerations.getOrDefault(local(declaration.getAttribute("type")), List.of());
        }
        final Element simpleType = firstChild(declaration, "simpleType");
        return simpleType == null
                ? List.of()
                : enumerationOf(simpleType);
    }

    private static List<String> enumerationOf(final Element simpleType) {
        final Element restriction = firstChild(simpleType, "restriction");
        if (restriction == null) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        final NodeList children = restriction.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof final Element child && XS.equals(child.getNamespaceURI())
                && "enumeration".equals(child.getLocalName()) && child.hasAttribute("value")) {
                values.add(child.getAttribute("value"));
            }
        }
        return values;
    }

    /**
     * A single particle of a choice, collected through the same walk as a container holds it.
     */
    private static Element wrap(final Element alternative) {
        final Element holder = alternative.getOwnerDocument().createElementNS(XS, "xs:sequence-alternative");
        holder.appendChild(alternative.cloneNode(true));
        return holder;
    }

    private static String render(final List<Particle> particles, final boolean open) {
        final StringBuilder text = new StringBuilder();
        for (final Particle particle : particles) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(particle.render(open));
        }
        return text.toString();
    }

    private static Set<String> names(final List<Particle> particles) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Particle particle : particles) {
            if (particle.name() != null) {
                names.add(particle.name());
            }
            if (particle.options() != null) {
                names.addAll(names(particle.options()));
            }
        }
        return names;
    }

    private static Optional<String> parentOf(final Element declaration) {
        for (Node node = declaration.getParentNode(); node != null; node = node.getParentNode()) {
            if (node instanceof final Element element && XS.equals(element.getNamespaceURI())
                && "element".equals(element.getLocalName()) && element.hasAttribute("name")) {
                return Optional.of(element.getAttribute("name"));
            }
            if (node instanceof final Element element && "complexType".equals(element.getLocalName())
                && element.hasAttribute("name")) {
                return Optional.of(element.getAttribute("name"));
            }
        }
        return Optional.empty();
    }

    private static Element firstChild(final Element parent, final String localName) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof final Element child && XS.equals(child.getNamespaceURI())
                && localName.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private static int minOccurs(final Element particle) {
        return particle.hasAttribute("minOccurs")
                ? Integer.parseInt(particle.getAttribute("minOccurs"))
                : 1;
    }

    private static int maxOccurs(final Element particle) {
        if (!particle.hasAttribute("maxOccurs")) {
            return 1;
        }
        final String value = particle.getAttribute("maxOccurs");
        return "unbounded".equals(value)
                ? Integer.MAX_VALUE
                : Integer.parseInt(value);
    }

    private static String local(final String qualified) {
        final int colon = qualified.indexOf(':');
        return colon < 0
                ? qualified
                : qualified.substring(colon + 1);
    }

    private static String limit(final String text) {
        return text.length() <= TEXT_LIMIT
                ? text
                : text.substring(0, TEXT_LIMIT) + " …";
    }

    private static Optional<Document> parse(final String xsd) {
        if (xsd == null || xsd.isBlank()) {
            return Optional.empty();
        }
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return Optional.of(factory.newDocumentBuilder().parse(new InputSource(new StringReader(xsd))));
        } catch (final ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * One thing an element may contain: a named child, a choice among {@code options}, or — as one option
     * of a choice — a {@code run} of children in order.
     */
    private record Particle(String name,
                            List<Particle> options,
                            int minOccurs,
                            int maxOccurs,
                            List<Particle> within,
                            List<Particle> run,
                            List<String> values) {

        String render(final boolean open) {
            final String body;
            if (options != null) {
                body = "(" + options.stream().map(option -> option.render(false)).collect(Collectors.joining(" | "))
                       + ")";
            } else if (run != null) {
                body = "(" + ContentModels.render(run, false) + ")";
            } else if (!values.isEmpty()) {
                body = name + " [" + String.join(" | ", values.size() > VALUES_SHOWN
                        ? values.subList(0, VALUES_SHOWN)
                        : values) + (values.size() > VALUES_SHOWN
                        ? " | …]"
                        : "]");
            } else {
                body = name + (open && within != null && !within.isEmpty()
                        ? " { " + ContentModels.render(within, false) + " }"
                        : "");
            }
            final String count = minOccurs == 0
                    ? (maxOccurs > 1
                            ? "*"
                            : "?")
                    : (maxOccurs > 1
                            ? "+"
                            : "");
            return body + count;
        }
    }
}
