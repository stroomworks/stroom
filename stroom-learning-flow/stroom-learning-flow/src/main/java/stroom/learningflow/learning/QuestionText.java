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

package stroom.learningflow.learning;

import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.extraction.XmlFragmentStep;
import stroom.learningflow.fragment.PipelineRuns;
import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.Records;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.LearnedUnknown;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.Template;
import stroom.learningflow.stage.Input;
import stroom.learningflow.transformation.XsltStep;
import stroom.util.shared.StoredError;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/// Turns each [Question] into the prompt text sent to the model, using the document's templates.
///
/// The system text carries the objective and the document's instructions. Each question then carries, as
/// it applies, the stream's feed and type, the sample, the real input the element will receive, the
/// previous configuration on a re-ask, and the feedback on what fell short. For a Data Splitter it carries
/// the mandatory `xsi:schemaLocation` and the ban on `ignoreErrors`; for a transform, the schema's common
/// mistakes and the warning against output that validates but says nothing. Every question states the form
/// the reply must take.
///
/// This class computes the blocks that a template's variables stand for (for example, `${feedback}` is the
/// whole "what fell short" list, or nothing), and [Templates] fills them in. Both the node's advisor and
/// the live test harness use it, so the harness measures the same prompt the node sends.
public final class QuestionText {

    /// The namespace a Data Splitter puts its records in, which the transformation rules describe.
    private static final String RECORDS = "records:2";
    // Event-logging's own rule against its untyped Data elements and its Unknown branch, which other schemas
    // don't have. It ends in a space, because the passage it goes into continues after it
    private static final String UNTYPED_RULE = "Do not put fields into Data elements or use the Unknown branch: "
                                               + "output that validates but says nothing scores as nothing. ";

    /// What each allowed element does, in one line, so that the model answers the chain question from
    /// descriptions it understands rather than a bare list of names.
    private static final Map<String, String> ELEMENTS = Map.of(
            DataSplitterStep.ELEMENT_TYPE,
            "parses raw text into records:2 XML with a Stroom Data Splitter 3.0 configuration",
            // Say plainly that this is not records:2: a stylesheet written for records:2 matches nothing in
            // this output, writes the input's text and no elements, and fails without Saxon raising anything
            JsonStep.ELEMENT_TYPE,
            "parses JSON into the XML of the http://www.w3.org/2013/XSL/json vocabulary — "
            + "map, array, string, number and boolean elements, each with a key attribute — "
            + "with no configuration",
            PipelineRuns.XML_PARSER,
            "parses XML input as it is, with no configuration",
            XmlFragmentStep.ELEMENT_TYPE,
            "wraps a stream of XML fragments — one element per line, with no root — "
            + "in a root so that they become one document, with no configuration",
            // The namespace is filled in from the document's target schema
            XsltStep.ELEMENT_TYPE,
            "transforms XML records into ${targetNamespace} events with an XSLT 2.0 stylesheet");

    /// The minimum bytes each example record gets, however many share the budget, so that each is shown
    /// enough to be recognisable.
    private static final int SHORTEST = 1000;

    private final Templates templates;
    private final String system;
    /// The schema this document's translations must produce, or null where it names none, in which case
    /// the prompt names event-logging 3.0.0.
    private final TargetSchema target;
    /// How much of the stream a question carries, in bytes: the Learning Flow's sample size limit. It
    /// matches the size of the sample the split is judged on, so the model sees all of what it is judged
    /// against.
    private final int sampleSizeLimit;

    private QuestionText(final Templates templates,
                         final String system,
                         final int sampleSizeLimit,
                         final TargetSchema target) {
        this.templates = templates;
        this.system = system;
        this.sampleSizeLimit = sampleSizeLimit;
        this.target = target;
    }

    /// Returns the prompt text for a document's flow: its templates, and a system text with its instructions
    /// and what its scorers will require of every event (the required fields and the business rules). These
    /// are stated up front so the model aims at them rather than learning them from a shortfall.
    ///
    /// @param doc The document.
    /// @return The question text.
    public static QuestionText of(final LearningFlowDoc doc) {
        return of(doc, AskedWith.UNKNOWN, null);
    }

    /// The same, naming the schema the document's translations must produce.
    ///
    /// @param doc    The document.
    /// @param target Its target schema, or null where it names none.
    /// @return The question text.
    public static QuestionText of(final LearningFlowDoc doc, final TargetSchema target) {
        return of(doc, AskedWith.UNKNOWN, target);
    }

    /// The same, using the wording a run was asked with, for resuming a parked run: its templates and its
    /// *Instructions to the AI* as they were, rather than as the Learning Flow has them now. A run that
    /// resumes after an edit or an upgrade then shows the model its own history in the wording it was first
    /// asked in.
    ///
    /// The requirements (the fields and rules the checks will require of every event) come from the
    /// document as it is now, not from the recording, because they are what the candidate will actually be
    /// judged against. Asking for outdated requirements would set the model up to fail.
    ///
    /// @param doc       The document.
    /// @param askedWith The wording the run was asked with, or [AskedWith#UNKNOWN] for a new run.
    /// @return The question text.
    public static QuestionText of(final LearningFlowDoc doc, final AskedWith askedWith) {
        return of(doc, askedWith, null);
    }

    /// The same, naming the schema the document's translations must produce.
    ///
    /// The target comes from the document as it is now, not from the recording, for the same reason as the
    /// requirements: it is what the candidate will actually be judged against.
    ///
    /// @param doc       The document.
    /// @param askedWith The wording the run was asked with, or [AskedWith#UNKNOWN] for a new run.
    /// @param target    Its target schema, or null where it names none.
    /// @return The question text.
    public static QuestionText of(final LearningFlowDoc doc,
                                  final AskedWith askedWith,
                                  final TargetSchema target) {
        final Templates templates = askedWith.known()
                ? askedWith.asTemplates()
                : Templates.of(doc.getFlow());
        final String instructions = askedWith.known()
                ? askedWith.instructions()
                : doc.getInstructions();
        final StringBuilder demands = new StringBuilder();
        for (final CheckSetting setting : doc.getChecks()) {
            if (!setting.isOn()) {
                continue;
            }
            if (setting.getParameters() instanceof final ExtractionQualityParameters quality
                && !quality.getRequiredFields().isEmpty()) {
                // These are scored as the share of events that carry each field, so they are described as
                // where values go, not as a requirement: a kind of record with no user is still an event.
                demands.append("\n\nEvents are scored on carrying a value in each of: ")
                        .append(String.join(", ", quality.getRequiredFields()))
                        .append(". Fill each wherever the record has a value for it; a record without one is "
                                + "still an event.");
            }
            if (setting.getParameters() instanceof final BusinessRulesParameters rules
                && !rules.getAssertions().isEmpty()) {
                demands.append("\n\nEvery event must satisfy these rules, each an XPath over the ")
                        .append(Objects.requireNonNullElse(recordElement(target), "record"))
                        .append(":");
                rules.getAssertions().forEach(rule -> demands.append("\n- ").append(rule.getName()).append(": ")
                        .append(rule.getXpath()));
            }
        }
        return new QuestionText(templates, templates.render(Template.SYSTEM,
                Map.of("instructions", instructions(instructions), "demands", demands.toString())),
                doc.getSampleSizeLimit(), target);
    }

    /// Returns question text from the built-in templates with only the given instructions, for a test or
    /// harness that has no document.
    ///
    /// @param instructions The instructions to the model, or null.
    /// @return The question text.
    public static QuestionText builtIn(final String instructions) {
        final Templates templates = Templates.builtIn();
        return new QuestionText(templates, templates.render(Template.SYSTEM,
                Map.of("instructions", instructions(instructions), "demands", "")),
                LearningFlowDoc.builder().uuid("built-in").name("built-in").build().getSampleSizeLimit(),
                null);
    }

    public static QuestionText of(final LearningFlow flow) {
        return of(LearningFlowDoc.builder().uuid("flow").name("flow").flow(flow).build());
    }

    private static String instructions(final String instructions) {
        return instructions == null || instructions.isBlank()
                ? ""
                : "\n\nThe document that governs this stage says:\n" + instructions.strip();
    }

    /// @return The system text: the objective, the document's instructions and its scorers' requirements.
    public String system() {
        return system;
    }

    public String render(final Question question) {
        return switch (question) {
            case Chain chain -> chain(chain);
            case Split split -> split(split);
            case TargetFor target -> target(target);
            case Configuration configuration -> configuration(configuration);
        };
    }

    private String chain(final Chain question) {
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        variables.put("elements", question.allowedElements().stream()
                .map(element -> "- " + element + ": " + describe(element))
                .collect(Collectors.joining("\n")));
        variables.put("sample", shown(question.sample().text()));
        variables.put("feedback", feedback(question.feedback()));
        return templates.render(Template.CHAIN, variables);
    }

    /// Describes what an element does in one line, with the target schema's namespace filled in where the
    /// line names one.
    ///
    /// @param element The element type.
    /// @return Its description.
    private String describe(final String element) {
        return ELEMENTS.getOrDefault(element, "a pipeline element")
                .replace("${targetNamespace}", target == null
                        ? TargetSchema.EVENT_LOGGING
                        : target.namespaceURI());
    }

    // Renders the split question, which asks for the record boundary and nothing else, so that the
    // questions that follow can be asked about records.
    private String split(final Split question) {
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        if (question.kind() != InputKind.TEXT) {
            // For XML, or JSON a parser turns into XML, the model names an element or key, not a document
            variables.put("sample", shown(question.sample().text()));
            variables.put("feedback", feedback(question.feedback()));
            return templates.render(question.kind() == InputKind.XML
                    ? Template.SPLIT_XML
                    : Template.SPLIT_JSON, variables);
        }
        variables.put("elementType", question.elementType());
        variables.put("documentType", question.documentType());
        variables.put("splitRules", templates.text(Template.SPLIT_RULES));
        variables.put("sample", shown(question.sample().text()));
        variables.put("feedback", feedback(question.feedback()));
        return templates.render(Template.SPLIT, variables);
    }

    // Renders the target question: what one kind of record should become, as an event or the word none.
    private String target(final TargetFor question) {
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        variables.put("kind", String.valueOf(question.kind()));
        variables.put("total", String.valueOf(question.total()));
        variables.put("transformationRules", rules(Template.TRANSFORMATION_RULES));
        variables.put("record", fenced(question.record()));
        variables.put("feedback", feedback(question.feedback()));
        variables.put("targetRecord", targetRecord());
        return templates.render(Template.TARGET, variables);
    }

    private String configuration(final Configuration question) {
        final boolean extraction = DataSplitterStep.ELEMENT_TYPE.equals(question.elementType());
        final Map<String, String> variables = new HashMap<>();
        variables.put("headers", headers(question.sample()));
        variables.put("elementType", question.elementType());
        variables.put("documentType", question.documentType());
        variables.put("rules", rules(extraction
                ? Template.EXTRACTION_RULES
                : Template.TRANSFORMATION_RULES));
        variables.put("input", shown(question.input()) + ownMarkup(extraction, question.input()));
        variables.put("split", split(question.split(), question.oneRecord(), question.records(),
                question.otherKinds()));
        variables.put("targets", targets(question.targets(), extraction));
        variables.put("previous", question.previousConfiguration() == null
                ? ""
                : "\nYour previous configuration was:\n" + fenced(question.previousConfiguration()) + "\n");
        variables.put("feedback", feedback(question.feedback()));
        if (question.admitting()) {
            // The exhaustion step's question: the configuration question with the form of a marked Unknown
            // event added, which no other question mentions.
            variables.put("marker", LearnedUnknown.MARKER);
            variables.put("admitted", fenced(LearnedUnknown.markup("the record kind")));
            return templates.render(Template.ADMISSION, variables);
        }
        return templates.render(Template.CONFIGURATION, variables);
    }

    // Renders one of the passages of rules the questions share, with the target schema filled in. A
    // passage is a template of its own, so it is rendered here rather than taken as text: the outer question
    // renders in one pass, and a slot inside a value it substitutes would be left as written.
    //
    // Where the document names no target, the passage names event-logging 3.0.0.
    private String rules(final Template template) {
        return templates.render(template, Map.of(
                "targetStructure", structure(),
                "aTargetRecord", TargetSchema.isEventLoggingOrNone(target)
                        ? "an Event"
                        : "one " + targetRecord(),
                "untypedRule", TargetSchema.isEventLoggingOrNone(target)
                        ? UNTYPED_RULE
                        : "",
                "targetNamespace", target == null
                        ? TargetSchema.EVENT_LOGGING
                        : target.namespaceURI(),
                "targetSchemaLocation", target == null
                        ? TargetSchema.EVENT_LOGGING + " file://event-logging-v3.0.0.xsd"
                        : target.schemaLocation(),
                "targetVersion", target == null || target.version() == null
                        ? "3.0.0"
                        : target.version(),
                "targetRoot", target == null || target.rootElement() == null
                        ? "Events"
                        : target.rootElement()));
    }

    // Returns the local name of the element one record becomes: event-logging's `Event`, or the target's own
    // where its schema makes it clear. Null where the schema doesn't make it clear. A null target means
    // event-logging.
    private static String recordElement(final TargetSchema target) {
        if (TargetSchema.isEventLoggingOrNone(target)) {
            return "Event";
        }
        return TargetStructure.recordElement(target).orElse(null);
    }

    // Returns the element one record becomes, as markup, e.g. `<Event>`, or where the schema doesn't make it
    // clear, a description of where it goes, e.g. "element under <Sightings>".
    private String targetRecord() {
        final String element = recordElement(target);
        if (element != null) {
            return "<" + element + ">";
        }
        return target.rootElement() == null
                ? "element the schema allows"
                : "element under <" + target.rootElement() + ">";
    }

    // Returns the passage describing what a document of the target schema holds.
    //
    // The built-in text is event-logging's, written by hand, and is used for a document targeting
    // event-logging or naming no target. For any other schema the passage is generated from the XSD. A
    // Learning Flow that overrides the template has said what it wants whichever schema it targets, so an
    // override always wins.
    private String structure() {
        if (templates.overrides(Template.TARGET_STRUCTURE)
            || target == null
            || target.isEventLogging()) {
            return templates.text(Template.TARGET_STRUCTURE);
        }
        return TargetStructure.of(target);
    }

    // Describes the settled record boundary for the configuration question: the configuration that cuts the
    // records, for a parser that must cut the same ones, or the element, children or array that hold one
    // record where the input is XML or JSON. Where the element is given one record at a time, it says that
    // instead.
    private String split(final Boundary split,
                         final boolean oneRecord,
                         final Records records,
                         final List<String> otherKinds) {
        if (oneRecord) {
            // The element will be run once per record, with one record each time. A stylesheet written for
            // the whole stream (counting its siblings, reaching into the document around it) would be
            // written for input it will never get. This is said whether or not there is a boundary to
            // describe, because it is a fact about the input in front of the model rather than about the
            // split that produced it.
            return "\nThe input below is **one record**: this configuration is run once for each record of "
                   + "the stream, with one record in front of it each time, exactly as shown. Produce the "
                   + "one event for the record you are given, and do not look outside it — there is "
                   + "nothing outside it to look at.\n" + carried(records) + otherKinds(otherKinds, records);
        }
        if (split == null) {
            return "";
        }
        if (split.configuration() != null) {
            return "\nThe record boundary is settled; this configuration cuts one record per unit, and yours must "
                   + "cut the same records while extracting every field:\n" + fenced(split.configuration()) + "\n";
        }
        if (split.array() != null) {
            return Boundary.ROOT.equals(split.array())
                    ? "\nThe record boundary is settled: each top-level JSON value is one record — in the parsed "
                      + "XML, each child of the root map, or each item of its one keyless array where the document "
                      + "is a top-level array. Produce one event per record.\n"
                    : "\nThe record boundary is settled: each item of the JSON array \"" + split.array() + "\" is one "
                      + "record — in the parsed XML, each child of the array element with key=\"" + split.array()
                      + "\". Produce one event per item, and nothing for the values around them.\n";
        }
        if (split.children() != null) {
            // Several kinds of record side by side under one container: the transform is given one child
            // at a time and must not assume they are all alike
            return Boundary.ROOT.equals(split.children())
                    ? "\nThe record boundary is settled: each child element of the root is one record, whatever "
                      + "it is called — the records are of several kinds. Produce one event per child, and "
                      + "handle each kind you are shown.\n"
                    : "\nThe record boundary is settled: each child element of <" + split.children() + "> is one "
                      + "record, whatever it is called — the records are of several kinds sitting side by side. "
                      + "Produce one event per child, handle each kind you are shown, and read the context you "
                      + "need from the attributes of the enclosing <" + split.children() + ">.\n";
        }
        return "\nThe record boundary is settled: each <" + split.element() + "> element is one record. Produce one "
               + "event per <" + split.element() + ">, and nothing for the elements around them.\n";
    }

    // Tells the model what the one record shown does not show: how many records there are, and whether they
    // are all alike. A configuration written from one record is run over every record, and a stream that
    // reports several kinds of thing has several shapes of record.
    private static String carried(final Records records) {
        if (records == null || records.total() == 0) {
            return "";
        }
        return records.kinds() > 1
                ? "The stream holds " + records.total() + " records of " + records.kinds() + " different "
                  + "shapes, and this is one of them: handle every shape it may be given, not only this "
                  + "one.\n"
                : "The stream holds " + records.total() + " records, all of this shape.\n";
    }

    private static String targets(final List<Target> targets, final boolean extraction) {
        if (targets.isEmpty()) {
            return "";
        }
        final StringBuilder text = new StringBuilder(extraction
                ? "\nWhat each kind of record must become — the records you emit must carry, as data values, "
                  + "every value these events take from the record:\n"
                : "\nWhat each kind of record must become — produce exactly these events for these records:\n");
        int kind = 0;
        for (final Target target : targets) {
            kind++;
            text.append("\nRecord kind ").append(kind).append(":\n").append(fenced(target.record())).append('\n');
            text.append(target.event()
                    .map(event -> "becomes:\n" + fenced(event) + "\n")
                    .orElse(extraction
                            ? "becomes no event. Still emit it as a record, whole; the transform drops it, and "
                              + "coverage measures what the split kept.\n"
                            : "becomes no event.\n"));
        }
        return text.toString();
    }

    private static String headers(final Sample sample) {
        if (sample.headers().isEmpty()) {
            return "";
        }
        return "The stream is described by:\n" + sample.headers().entrySet().stream()
                .map(entry -> "- " + entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("\n")) + "\n\n";
    }

    private static String feedback(final List<StoredError> feedback) {
        if (feedback.isEmpty()) {
            return "";
        }
        return "\nWhat fell short:\n" + feedback.stream()
                .map(error -> "- " + error.getSeverity().getDisplayValue() + ": " + error.getMessage())
                .collect(Collectors.joining("\n")) + "\n";
    }

    private static String fenced(final String document) {
        return "```xml\n" + document.strip() + "\n```";
    }

    // Shows one record of each of the stream's other kinds. The configuration is run over every record, and
    // one written from a login record alone will drop the logouts. The caller passes these only where the
    // flow has not settled its targets, since targets already show a record of each kind.
    //
    // This comes after the one-record instruction, not before it, because these are examples of the
    // *other* runs this configuration will have, not context for the record in front of it.
    //
    // The number of examples is capped ([TargetChecks#REPRESENTATIVES]), while [Records#kinds()] says how
    // many kinds there are. Where the cap applies, the question says so rather than claiming to show one of
    // each, because a model told it has seen every shape when it has seen three of five will write a
    // configuration that drops the other two.
    private String otherKinds(final List<String> shown, final Records records) {
        if (shown == null || shown.isEmpty()) {
            return "";
        }
        final int others = records == null || records.kinds() <= 1
                ? shown.size()
                : records.kinds() - 1;
        final StringBuilder text = new StringBuilder("\nIt will be given the stream's other shapes in the "
                                                    + "same way — one record at a time, each on its own run. ");
        text.append(others <= shown.size()
                ? "Here is one record of each of them:\n"
                : "Here is one record of " + shown.size() + " of the " + others + " other shapes; there are "
                  + (others - shown.size()) + " more this question does not show:\n");
        // The examples share one sample's worth of bytes (with a minimum each), because this block is
        // resent with every re-ask, and for a feed with large records it would otherwise treble the size of
        // the question and its token cost.
        final int each = Math.max(sampleSizeLimit / shown.size(), SHORTEST);
        for (final String record : shown) {
            text.append(shown(record, each)).append('\n');
        }
        return text.toString();
    }

    // Warns, next to the input, when a transform is given the stream's own XML rather than a Data Splitter's
    // records. The transformation rules describe the usual case and say `records:2`, but a chain with no
    // Data Splitter gives the element the feed's XML as it arrived. A stylesheet that sets
    // `xpath-default-namespace="records:2"` over that matches nothing, writes the input's text and no
    // elements, and fails without Saxon raising anything. Models have been seen to make this mistake
    // repeatedly.
    private static String ownMarkup(final boolean extraction, final String input) {
        if (extraction || input == null || !input.stripLeading().startsWith("<") || input.contains(RECORDS)) {
            return "";
        }
        return "\nThis input is the stream's own markup and not " + RECORDS + ": match the elements as they "
               + "are named above, and do not set xpath-default-namespace to " + RECORDS + ".\n";
    }

    // Returns the input, fenced, cut to the Learning Flow's sample size limit with a note where it did not
    // fit. A sample is already cut to that limit before it gets here (`LearningPrefix.learningPrefix`), so
    // what this cuts is, for example, a single record larger than the whole sample would be.
    private String shown(final String input) {
        return shown(input, sampleSizeLimit);
    }

    // The same, to a budget chosen by the caller, so that several records shown in one question can share
    // what one would have had. The budget is in **bytes**, as the setting is labelled.
    private static String shown(final String input, final int budget) {
        if (input == null) {
            return "(nothing)";
        }
        final int length = Input.bytes(input);
        if (length <= budget) {
            return fenced(input);
        }
        final String cut = Input.withinBytes(input, budget);
        return fenced(cut) + "\n(cut after " + Input.bytes(cut) + " of " + length + " bytes)";
    }
}
