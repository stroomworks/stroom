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

import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.Template;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The built-in text of the questions put to the model, and the rendering of a template into a prompt.
///
/// Each [Template] has a built-in text with `${variable}` slots. A Learning Flow document overrides only the
/// templates it names, and every other template uses the built-in text of [#VERSION]. A variable renders a
/// whole *block* (for example, the whole "what fell short" list, or nothing), so a template needs no
/// conditionals. The blocks are computed by [QuestionText], which fills them in from the attempt.
///
/// The built-in text has been refined by testing against real models: the worked Data Splitter examples, the
/// schema's common mistakes, the warning against degenerate output, "a header line is a record too". A change
/// to it raises [#VERSION], so that documents and runs recorded against an earlier version can be told
/// apart.
public final class Templates {

    /// The version of the built-in text. It is raised whenever the built-in text changes in a way that a
    /// stored run should be told apart from.
    public static final int VERSION = 11;

    private static final Map<Template, String> BUILT_IN = new EnumMap<>(Template.class);

    static {
        BUILT_IN.put(Template.SYSTEM, """
                You configure one stage of a Stroom data pipeline so that it turns the streams a feed sends into \
                well-formed, meaningful events. You are asked a series of questions: which chain of elements \
                fits a sample of the data, then the configuration document for each element in turn, each \
                shown the real output of the elements before it. When a candidate falls short you are asked \
                again with exactly what lost the marks; fix that. Answer each question in the form it asks for \
                and nothing else.${instructions}${demands}""");
        BUILT_IN.put(Template.CHAIN, """
                Which chain of elements turns this stream into events?

                ${headers}The elements you may use:
                ${elements}

                A sample of the stream:
                ${sample}

                Raw text needs a parser first, to cut it into records; a stream that is already XML needs \
                none, and the chain begins with the transform.
                ${feedback}
                Reply with the element names in order, joined by ->, for example "DSParser -> XSLTFilter" \
                for text or "XSLTFilter" for XML, and nothing else.""");
        BUILT_IN.put(Template.SPLIT, """
                Before anything is extracted, settle what one record is in this stream.

                ${headers}Write the ${documentType} document for the ${elementType} element that cuts the input \
                into records and emits each record whole, as one field named "record", and extracts nothing \
                else. Often one record is one line; where a record spans several lines, the boundary is what \
                separates records, not lines. Every line must belong to some record.

                ${splitRules}

                A sample of the stream:
                ${sample}
                ${feedback}
                Reply with the ${documentType} document as a single fenced XML code block and nothing else.""");
        BUILT_IN.put(Template.SPLIT_XML, """
                Before anything is extracted, settle what one record is in this stream.

                ${headers}The input is already XML. Settle what one record is, in one of two ways.

                Where every record is the same element, name it: the element that occurs once per record, \
                whose elements together hold nearly the whole document, and which the transform will turn \
                into one event each. Not the root, which holds every record; not a field within a record. \
                Reply with its local name alone — no prefix, no angle brackets.

                Where records of several kinds sit side by side under one container — an audit export \
                whose children are authentication, transfer, policyChange and so on, each a different \
                element — name the container and its children: reply container/* , for example batch/* . \
                Reply * where the records are the root's own children. Each child is then one record, \
                whatever it is called.

                A sample of the stream:
                ${sample}
                ${feedback}
                Reply with one of those two forms and nothing else.""");
        BUILT_IN.put(Template.SPLIT_JSON, """
                Before anything is extracted, settle what one record is in this stream.

                ${headers}The input is JSON, which the JSONParser turns into XML of the \
                http://www.w3.org/2013/XSL/json vocabulary: a map for an object, an array for an array, each \
                value an element named for its type with the object key as its key attribute. Name the array \
                whose items are the records — the array that holds one item per record and nearly the whole \
                document — by its key; or reply root where each top-level value is one record, as in JSON \
                lines, or where the document is one top-level array of records, which the parser shows as a \
                keyless array under the root map. Not a field within a record.

                A sample of the stream:
                ${sample}
                ${feedback}
                Reply with the key alone, or the word root, and nothing else.""");
        BUILT_IN.put(Template.TARGET, """
                Record kind ${kind} of ${total}: what event should this record become?

                ${headers}${transformationRules}

                The record:
                ${record}

                Write the single ${targetRecord} this record should become, with every value the record \
                carries in a typed element and the constants the stream's description gives. If this kind of record \
                should produce no event at all — a header, a comment — reply with the single word none.
                ${feedback}
                Reply with the event as a single fenced XML code block, or the word none, and nothing else.""");
        BUILT_IN.put(Template.CONFIGURATION, """
                Write the ${documentType} document for the ${elementType} element.

                ${headers}${rules}

                The element will receive this input:
                ${input}
                ${split}${targets}${previous}${feedback}
                Reply with the ${documentType} document as a single fenced XML code block and nothing else.""");
        BUILT_IN.put(Template.ADMISSION, """
                Write the ${documentType} document for the ${elementType} element once more. Your candidates \
                for it are spent, and the records named in the feedback below still cannot be translated: no \
                event the schema allows says what they are.

                ${headers}${rules}

                For each kind of record the feedback names that you cannot translate, add a match that admits \
                it: an Event whose EventDetail holds TypeId and then exactly this, with the record kind as the \
                value, and nothing else in the detail:
                ${admitted}
                The Data item named ${marker} is what marks the event as a learned admission; without it an \
                Unknown is refused, and with it the event is accepted and served. Admit only what cannot be \
                translated. Every match that already translates a record must be kept exactly as it is, and \
                every record the feedback shows as translating must still translate.

                The element will receive this input:
                ${input}
                ${split}${targets}${previous}${feedback}
                Reply with the ${documentType} document as a single fenced XML code block and nothing else.""");
        BUILT_IN.put(Template.SPLIT_RULES, """
            The root element is <dataSplitter xmlns="data-splitter:3" \
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">; the \
            schemaLocation is mandatory. For records that are lines:

            ```xml
            <?xml version="1.0" encoding="UTF-8"?>
            <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
              <split delimiter="\\n">
                <group value="$1">
                  <regex pattern="^(.+)$">
                    <data name="record" value="$1"/>
                  </regex>
                </group>
              </split>
            </dataSplitter>
            ```

            For records of several lines, split on what separates records — a blank line, a line that begins a \
            record — and capture the whole record as the one field. Where a field is quoted and may hold the \
            delimiter, doubled quotes or line breaks, do not split on lines: match each record with one <regex> \
            over the stream, in which "(?:[^"]|"")*" matches one quoted field. Do not use ignoreErrors.""");
        BUILT_IN.put(Template.EXTRACTION_RULES, """
            The root element is <dataSplitter xmlns="data-splitter:3" \
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">. \
            The schemaLocation is mandatory; a configuration without it is rejected.

            The structure is strict, so follow this shape exactly. A <split delimiter="\\n"> cuts the input \
            into lines; inside it, one <group value="$1"> takes each line as the text the elements inside \
            the group match. Inside the group put a <regex pattern="..."> (or a further <split>); the \
            <data name="..." value="$n"/> elements go directly inside that <regex> or <split>, one per \
            captured field, never wrapped in another <group>. Each line that matches becomes one record. \
            <var id="..."/> stores a match for later reference as $id$n. A worked example for lines of \
            "time,user,place,action":

            ```xml
            <?xml version="1.0" encoding="UTF-8"?>
            <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
              <split delimiter="\\n">
                <group value="$1">
                  <regex pattern="^([^,]+),([^,]+),([^,]+),([^,]+)$">
                    <data name="time" value="$1"/>
                    <data name="user" value="$2"/>
                    <data name="place" value="$3"/>
                    <data name="action" value="$4"/>
                  </regex>
                </group>
              </split>
            </dataSplitter>
            ```

            Where the input has more than one kind of line, put one <regex> per kind inside the same group. \
            Where a field is quoted and may hold the delimiter, doubled quotes or line breaks, use a <regex> \
            over the stream in place of the <split>, matching each record from its start, with \
            "(?:[^"]|"")*" for a quoted field. Do not use ignoreErrors. Every line of the input should be \
            consumed by a match that emits a record; a line the configuration quietly drops counts against it.

            Where the first line is a header naming the fields, read it into a variable and name every \
            record's data from it, so that the names travel with each record and a reordered column still \
            lands in the right field; the header is then not a record. A worked example for a file whose \
            first line is "time,user,place,action":

            ```xml
            <?xml version="1.0" encoding="UTF-8"?>
            <dataSplitter xmlns="data-splitter:3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="data-splitter:3 file://data-splitter-v3.0.xsd" version="3.0">
              <split delimiter="\\n" maxMatch="1">
                <group>
                  <split delimiter=",">
                    <var id="heading"/>
                  </split>
                </group>
              </split>
              <split delimiter="\\n">
                <group value="$1">
                  <split delimiter=",">
                    <data name="$heading$1" value="$1"/>
                  </split>
                </group>
              </split>
            </dataSplitter>
            ```""");
        BUILT_IN.put(Template.TARGET_STRUCTURE, """
            holding one <Event> per record. Every Event needs \
            EventTime/TimeCreated, EventSource and EventDetail, in that order. EventSource needs System (Name, \
            Environment), Generator and one of Device, Client, Server or Door, and should name the User where the \
            input has one. EventDetail needs TypeId and then exactly one branch describing what happened, from \
            this list and no other: Authenticate, Authorise, Search, Copy, Move, Create, View, Import, Export, \
            Update, Delete, Process, Print, Install, Uninstall, Network, AntiMalware, Alert, Send, Receive. \
            Authenticate holds Action first (Logon, Logoff, ...), then User with its Id; other branches hold \
            their own typed elements — a Description is a child of the branch's element, never the branch.""");
        BUILT_IN.put(Template.TRANSFORMATION_RULES, """
            The stylesheet reads records:2 (use xpath-default-namespace="records:2") and writes \
            ${targetNamespace} events: <${targetRoot} xmlns="${targetNamespace}" \
            xsi:schemaLocation="${targetSchemaLocation}" Version="${targetVersion}"> ${targetStructure} \
            Element order is enforced everywhere. Write every datetime as an ISO 8601 instant in UTC with \
            milliseconds, e.g. 2026-11-11T09:18:19.000Z. Where the input's timestamp leaves out the year, the \
            date or the time zone, do not guess and never write a year as a literal: parse it with \
            stroom:format-date(value, pattern), or stroom:format-date(value, pattern, timeZone) where you know \
            the zone it was written in, declaring xmlns:stroom="stroom" on the stylesheet. That takes whatever \
            the timestamp leaves out from the time this stream was received, so the same translation is right \
            for a stream received next year. Never read a local time as if it were UTC. \
            Every field the input carries should land in a typed element. ${untypedRule}\
            Every record must be accounted for: produce ${aTargetRecord} for each one. Where a record is \
            deliberately not an event - a header line, a column banner, a heartbeat, a comment - write \
            <Skipped xmlns="learningflow:skips" reason="..."/> in its place, saying briefly why. The marker \
            is counted, shown to the reviewer and removed before anything is written, so it is never a way \
            to make an awkward record go away: a record you cannot read is a record to ask about, not to \
            skip.""");
    }

    private final Map<Template, String> overrides;

    private Templates(final Map<Template, String> overrides) {
        this.overrides = overrides;
    }

    /// Returns the templates a document's flow uses: its overrides, and the built-ins for the rest.
    ///
    /// @param flow The document's learning flow.
    /// @return The templates.
    public static Templates of(final LearningFlow flow) {
        return new Templates(flow.getTemplates());
    }

    public static Templates builtIn() {
        return new Templates(Map.of());
    }

    /// Returns the templates a run was asked with, read back from what was recorded against its attempt.
    /// Every template is already resolved there (the Learning Flow's own text where it overrode one, the
    /// built-in otherwise), so the recorded text takes precedence over today's built-in. That way a parked
    /// run resumes in the wording it began with, and a rebuilt prompt is the one that was actually sent.
    ///
    /// A template the recording does not name falls back to the built-in. That happens when a template is
    /// added to the code after a run was opened.
    ///
    /// @param recorded The template set as the run used it.
    /// @return Templates that render with that wording.
    public static Templates ofRecorded(final Map<Template, String> recorded) {
        return new Templates(recorded == null
                ? Map.of()
                : Map.copyOf(recorded));
    }

    public static String builtIn(final Template template) {
        return BUILT_IN.get(template);
    }

    public static Map<Template, String> builtIns() {
        return Map.copyOf(BUILT_IN);
    }

    /// Whether the document overrides a template rather than using the built-in text.
    ///
    /// @param template The template.
    /// @return Whether it is overridden.
    public boolean overrides(final Template template) {
        return overrides.containsKey(template);
    }

    /// Returns the text the document uses for a template: its own where it overrides it, else the built-in.
    ///
    /// @param template The template.
    /// @return The text.
    public String text(final Template template) {
        return overrides.getOrDefault(template, BUILT_IN.get(template));
    }

    /// Renders a template by filling in its variables. This is done in one pass, so that a value which
    /// happens to contain `${...}` is not treated as a slot. A slot the variables do not name is left as
    /// written; validating the document ([#problems]) catches such slots first.
    ///
    /// @param template  The template.
    /// @param variables The value of each variable, by name.
    /// @return The rendered text.
    public String render(final Template template, final Map<String, String> variables) {
        final String text = text(template);
        final StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        for (final Slot slot : scan(text)) {
            final String value = variables.get(slot.name(text));
            out.append(text, from, slot.open());
            out.append(value == null
                    ? text.substring(slot.open(), slot.close() + 1)
                    : value);
            from = slot.close() + 1;
        }
        out.append(text, from, text.length());
        return out.toString();
    }

    /// Checks a flow's template overrides for slots that name a variable the template does not have.
    ///
    /// @param flow The learning flow.
    /// @return A description of each problem, or an empty list where every override can be rendered.
    public static List<String> problems(final LearningFlow flow) {
        final List<String> problems = new ArrayList<>();
        flow.getTemplates().forEach((template, text) -> {
            final Set<String> allowed = Set.of(template.getVariables());
            for (final String name : slots(text)) {
                if (!allowed.contains(name)) {
                    problems.add("The " + template.getDisplayValue().toLowerCase() + " template names ${" + name
                                 + "}, which it does not have; it may use " + (allowed.isEmpty()
                            ? "no variables"
                            : String.join(", ", allowed.stream().sorted().map(v -> "${" + v + "}").toList())));
                }
            }
        });
        return problems;
    }

    static List<String> slots(final String text) {
        final List<String> names = new ArrayList<>();
        for (final Slot slot : scan(text)) {
            names.add(slot.name(text));
        }
        return names;
    }

    /// The `${name}` slots of a text, in order. Scanning stops at a `${` with no `}` after it.
    private static List<Slot> scan(final String text) {
        final List<Slot> slots = new ArrayList<>();
        int from = 0;
        while (true) {
            final int open = text.indexOf("${", from);
            if (open < 0) {
                return slots;
            }
            final int close = text.indexOf('}', open);
            if (close < 0) {
                return slots;
            }
            slots.add(new Slot(open, close));
            from = close + 1;
        }
    }


    // --------------------------------------------------------------------------------


    /// One `${name}` slot of a text.
    ///
    /// @param open  The index of its `$`.
    /// @param close The index of its `}`.
    private record Slot(int open, int close) {

        private String name(final String text) {
            return text.substring(open + 2, close);
        }
    }
}
