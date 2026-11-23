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

/**
 * The built-in text of the conversation and how a template is rendered (design 01 §10.2, A33). Each
 * {@link Template} has a built-in text with {@code ${variable}} slots; a document overrides only the
 * templates it names and every other follows the built-in of {@link #VERSION}. A variable renders a
 * <em>block</em> — the whole "what fell short" list, or nothing — so a template needs no conditionals;
 * the blocks are computed by {@link QuestionText}, which is where the words meet the attempt.
 * <p>
 * The built-in text is the conversation as measured (design 02 §6.2, §6.3): the worked Data Splitter
 * example, the schema's failure modes, the degeneracy trap, "a header line is a record too". A finding
 * that changes it raises {@link #VERSION}, and a document saved against an earlier version says so.
 */
public final class Templates {

    /**
     * Raised when the built-in text changes in a way a stored run should be told apart from.
     */
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

    /**
     * The templates a document's flow reads by: its overrides over the built-ins.
     */
    public static Templates of(final LearningFlow flow) {
        return new Templates(flow.getTemplates());
    }

    public static Templates builtIn() {
        return new Templates(Map.of());
    }

    /// The templates a run was asked with, read back from what was recorded against its attempt
    /// (decision N9). Every template is already resolved there — the Learning Flow's own where it
    /// overrode one, the built-in otherwise — so the recorded text wins over today's built-in, which is
    /// the whole point: a parked run resumes in the words it began in, and a rebuilt prompt is the one
    /// that was sent.
    ///
    /// A template the recording does not name falls back to the built-in, which is what happens when a
    /// template is added to the code after a run was opened.
    ///
    /// @param recorded The template set as the run used it.
    /// @return Templates that render in those words.
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

    /// Whether this document says what a template should say, rather than following the built-in.
    ///
    /// @param template The template.
    /// @return Whether it is overridden.
    public boolean overrides(final Template template) {
        return overrides.containsKey(template);
    }

    /**
     * The text this document uses for a template: its own where it overrides, else the built-in.
     */
    public String text(final Template template) {
        return overrides.getOrDefault(template, BUILT_IN.get(template));
    }

    /**
     * The template with its variables filled, in one pass, so a value that happens to contain a slot is
     * not read as one. A slot the variables do not name is left as written; validation catches it first.
     */
    public String render(final Template template, final Map<String, String> variables) {
        final String text = text(template);
        final StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (true) {
            final int open = text.indexOf("${", from);
            if (open < 0) {
                out.append(text, from, text.length());
                return out.toString();
            }
            final int close = text.indexOf('}', open);
            if (close < 0) {
                out.append(text, from, text.length());
                return out.toString();
            }
            final String name = text.substring(open + 2, close);
            final String value = variables.get(name);
            out.append(text, from, open);
            out.append(value == null
                    ? text.substring(open, close + 1)
                    : value);
            from = close + 1;
        }
    }

    /**
     * What is wrong with a definition's templates: a slot naming a variable the template does not have.
     * Empty where every override renders.
     */
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
        int from = 0;
        while (true) {
            final int open = text.indexOf("${", from);
            if (open < 0) {
                return names;
            }
            final int close = text.indexOf('}', open);
            if (close < 0) {
                return names;
            }
            names.add(text.substring(open + 2, close));
            from = close + 1;
        }
    }
}
