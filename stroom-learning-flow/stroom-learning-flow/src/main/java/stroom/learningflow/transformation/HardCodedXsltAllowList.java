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

package stroom.learningflow.transformation;

import java.util.Map;
import java.util.Set;

/// The allow-list as this release has it (decision N1, option C, first stage). Everything here computes its
/// answer from its arguments, the record being translated, or the stream's own metadata; nothing reads
/// another feed, reaches off the node, writes anything, drops a record, or gives a different answer on a
/// second run over the same input.
///
/// The second of those matters as much as the first: judging and serving must agree, or a candidate is
/// promoted on a score the serving run cannot reproduce. That is why `current-dateTime()` and
/// `stroom:random()` are absent though neither leaves the node.
///
/// Track 2 step 6 moves this onto the Egress Policy screen, where a site can add a function it has checked
/// without waiting for a release. Until then, changing the list means a release.
final class HardCodedXsltAllowList implements AiXsltAllowList {

    static final AiXsltAllowList INSTANCE = new HardCodedXsltAllowList();

    /// The XSLT elements a translation needs: match a record, choose between shapes, pull out values and
    /// write the result.
    ///
    /// Not here, and refused: `result-document` (writes files), `include`, `import` and `import-schema`
    /// (read them), `evaluate` (runs an XPath built at run time, which no static check can see),
    /// `message` (`terminate="yes"` abandons the transform, and an unwrapped message is logged as an
    /// `ERROR`, which drops records from the output — the declared-skips design settled on a marker in the
    /// output instead, so this entry stands, ruling A148), `namespace-alias`, `output` and
    /// `character-map` (change serialisation
    /// under the writer), and `script` and `use-package`.
    private static final Set<String> INSTRUCTIONS = Set.of(
            "stylesheet", "transform", "template", "apply-templates", "call-template", "apply-imports",
            "next-match", "param", "with-param", "variable", "value-of", "copy", "copy-of", "sequence",
            "element", "attribute", "text", "comment", "processing-instruction", "if", "choose", "when",
            "otherwise", "for-each", "for-each-group", "sort", "number", "function", "key", "strip-space",
            "preserve-space", "attribute-set", "fallback", "document", "analyze-string", "matching-substring",
            "non-matching-substring", "perform-sort", "decimal-format", "mode", "on-empty", "on-non-empty",
            "where-populated", "iterate", "break", "next-iteration", "merge", "merge-source", "merge-key",
            "merge-action", "try", "catch", "assert", "accumulator", "accumulator-rule", "global-context-item",
            "context-item", "map", "map-entry", "array", "array-member");

    /// The standard XPath and XSLT functions a translation needs: strings, numbers, dates, sequences, nodes
    /// and regular expressions.
    ///
    /// Not here, and refused: `unparsed-text`, `unparsed-text-lines`, `unparsed-text-available`, `document`,
    /// `doc`, `doc-available`, `collection`, `uri-collection` and `json-doc`, which read files and make
    /// outbound requests; `environment-variable` and `available-environment-variables`, which read secrets
    /// passed in the environment; `function-lookup`, `function-name` and `load-xquery-module`, which call a
    /// function whose name never appears as a call; `transform`, which runs another stylesheet;
    /// `current-date`, `current-time`, `current-dateTime`, `random-number-generator` and `generate-id`,
    /// which do not repeat.
    private static final Set<String> FUNCTIONS = Set.of(
            // strings
            "string", "concat", "string-join", "substring", "string-length", "normalize-space",
            "normalize-unicode", "upper-case", "lower-case", "translate", "contains", "starts-with",
            "ends-with", "substring-before", "substring-after", "string-to-codepoints",
            "codepoints-to-string", "compare", "codepoint-equal", "encode-for-uri", "iri-to-uri",
            "escape-html-uri", "char",
            // regular expressions
            "matches", "replace", "tokenize", "analyze-string",
            // numbers
            "number", "abs", "ceiling", "floor", "round", "round-half-to-even", "format-number", "sum",
            "avg", "min", "max", "count",
            // booleans
            "boolean", "not", "true", "false",
            // dates and times, formatting only; the current date and time are not here
            "format-date", "format-dateTime", "format-time", "year-from-date", "month-from-date",
            "day-from-date", "hours-from-dateTime", "minutes-from-dateTime", "seconds-from-dateTime",
            "year-from-dateTime", "month-from-dateTime", "day-from-dateTime", "hours-from-time",
            "minutes-from-time", "seconds-from-time", "timezone-from-date", "timezone-from-dateTime",
            "timezone-from-time", "adjust-date-to-timezone", "adjust-dateTime-to-timezone",
            "adjust-time-to-timezone", "days-from-duration", "hours-from-duration", "minutes-from-duration",
            "seconds-from-duration", "months-from-duration", "years-from-duration", "dateTime",
            // sequences
            "empty", "exists", "distinct-values", "index-of", "insert-before", "remove", "reverse",
            "subsequence", "unordered", "head", "tail", "for-each", "filter", "fold-left", "fold-right",
            "for-each-pair", "zero-or-one", "one-or-more", "exactly-one", "deep-equal",
            // nodes
            "name", "local-name", "namespace-uri", "node-name", "root", "position", "last", "nilled",
            "data", "lang", "has-children", "innermost", "outermost", "path",
            // maps and arrays, which XSLT 3.0 templates use to carry values about
            "parse-json", "json-to-xml", "xml-to-json",
            // types and errors
            "error", "trace", "nan", "parse-xml", "parse-xml-fragment", "serialize",
            // keys and ids, within the document being translated
            "key", "id", "idref", "element-with-id", "default-collation", "static-base-uri",
            "resolve-QName", "QName", "prefix-from-QName", "local-name-from-QName",
            "namespace-uri-from-QName", "namespace-uri-for-prefix", "in-scope-prefixes");

    /// Stroom's own functions that compute their answer from their arguments or from the stream being
    /// translated, which is the stream the candidate is judged on (decision N1's last group).
    ///
    /// `format-date` is here on assumption AS6: it is pure provided the stream's metadata is set, which
    /// Track 1 task 3 makes the worker do for each attempt. Without that, judging and serving would take
    /// the missing year from different clocks.
    ///
    /// Not here, and refused: `http-call`, `fetch-json`, `send-email`, `ask-ai`, `host-name` and
    /// `host-address`, which leave the node; `lookup`, `bitmap-lookup`, `dictionary`, `manifest-for-id`,
    /// `meta-stream-for-id` and `parent-for-id`, which read beyond this stream with the processing user's
    /// access; `log`, `add-meta`, `put` and `get`, which have side effects — an ERROR or FATAL from `log`
    /// drops records from the output quietly, and a candidate that drops its awkward records can still
    /// score well; and `current-time`, `current-unix-time`, `random` and `random-integer`, which do not
    /// repeat.
    private static final Set<String> STROOM_FUNCTIONS = Set.of(
            // dates, strings and conversions: pure, given the stream's metadata for the missing year
            "format-date", "parse-date", "to-date", "to-dateTime", "hash", "hex-to-string",
            "hex-to-dec", "dec-to-hex", "numeric-ip", "ip-in-cidr", "cidr-to-numeric-ip-range",
            "encode-url", "decode-url", "parse-uri", "json-to-xml", "shannon-entropy",
            // the stream being translated, and nothing else
            "meta", "meta-keys", "meta-attribute", "manifest", "meta-stream", "feed-name", "pipeline-name",
            "source", "source-id", "stream-id", "parent-id", "part-no", "record-no", "line-from", "line-to",
            "col-from", "col-to");

    /// Why a refused name is refused, by group, so that the model is told what is wrong rather than only
    /// that something is.
    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("unparsed-text", "it reads files from the node"),
            Map.entry("unparsed-text-lines", "it reads files from the node"),
            Map.entry("unparsed-text-available", "it reads files from the node"),
            Map.entry("doc", "it reads files from the node, or makes an outbound request"),
            Map.entry("doc-available", "it reads files from the node, or makes an outbound request"),
            Map.entry("collection", "it lists files on the node"),
            Map.entry("uri-collection", "it lists files on the node"),
            Map.entry("json-doc", "it reads files from the node, or makes an outbound request"),
            Map.entry("environment-variable", "it reads the node's environment, which may hold secrets"),
            Map.entry("available-environment-variables",
                    "it reads the node's environment, which may hold secrets"),
            Map.entry("function-lookup", "it calls a function whose name never appears as a call"),
            Map.entry("function-name", "it calls a function whose name never appears as a call"),
            Map.entry("transform", "it runs another stylesheet"),
            Map.entry("current-date", "it does not give the same answer twice, so judging and serving "
                                      + "would disagree"),
            Map.entry("current-time", "it does not give the same answer twice, so judging and serving "
                                      + "would disagree"),
            Map.entry("current-dateTime", "it does not give the same answer twice, so judging and serving "
                                          + "would disagree"),
            Map.entry("current-unix-time", "it does not give the same answer twice, so judging and serving "
                                           + "would disagree"),
            Map.entry("random", "it does not give the same answer twice, so judging and serving would "
                                + "disagree"),
            Map.entry("random-integer", "it does not give the same answer twice, so judging and serving "
                                        + "would disagree"),
            Map.entry("random-number-generator", "it does not give the same answer twice, so judging and "
                                                 + "serving would disagree"),
            Map.entry("generate-id", "it does not give the same answer twice, so judging and serving would "
                                     + "disagree"),
            Map.entry("http-call", "it sends this stream's data off the node"),
            Map.entry("fetch-json", "it sends this stream's data off the node"),
            Map.entry("send-email", "it sends this stream's data off the node"),
            Map.entry("ask-ai", "it sends this stream's data off the node"),
            Map.entry("host-name", "it makes a DNS lookup, which carries data off the node"),
            Map.entry("host-address", "it makes a DNS lookup, which carries data off the node"),
            Map.entry("lookup", "it reads reference data beyond this stream"),
            Map.entry("bitmap-lookup", "it reads reference data beyond this stream"),
            Map.entry("dictionary", "it reads a dictionary beyond this stream"),
            Map.entry("manifest-for-id", "it reads another stream's metadata"),
            Map.entry("meta-stream-for-id", "it reads another stream's metadata"),
            Map.entry("parent-for-id", "it reads another stream's metadata"),
            Map.entry("log", "an ERROR or FATAL from it drops records from the output, which a translation "
                             + "must not do quietly"),
            Map.entry("add-meta", "it changes the stream's metadata"),
            Map.entry("put", "it carries state between records"),
            Map.entry("get", "it carries state between records"),
            Map.entry("result-document", "it writes files on the node"),
            Map.entry("include", "it reads another stylesheet from the node"),
            Map.entry("import", "it reads another stylesheet from the node"),
            Map.entry("import-schema", "it reads a schema from the node"),
            Map.entry("evaluate", "it runs an XPath built while the transform runs, which no check can read"),
            Map.entry("message", "it is a side effect: an unwrapped message is logged as an ERROR, which drops "
                                + "records, and 'terminate' abandons the transform. To say a record is "
                                + "deliberately not an event, write "
                                + "<Skipped xmlns=\"learningflow:skips\" reason=\"...\"/> in its place"),
            Map.entry("script", "it runs code that is not XSLT"),
            Map.entry("use-package", "it reads another stylesheet from the node"));

    /// @return Every `stroom:` function on the list, for the harness to say which it has no stand-in for.
    static Set<String> stroomFunctions() {
        return STROOM_FUNCTIONS;
    }

    private HardCodedXsltAllowList() {
    }

    @Override
    public boolean allowsInstruction(final String localName) {
        return INSTRUCTIONS.contains(localName);
    }

    @Override
    public boolean allowsFunction(final String namespaceUri, final String localName) {
        if (namespaceUri == null) {
            return false;
        }
        return switch (namespaceUri) {
            // A constructor such as xs:integer(…) only casts, and the schema namespace holds nothing else
            case SCHEMA_NAMESPACE -> true;
            case FUNCTIONS_NAMESPACE -> FUNCTIONS.contains(localName);
            case STROOM_NAMESPACE -> STROOM_FUNCTIONS.contains(localName);
            default -> false;
        };
    }

    @Override
    public String reasonAgainst(final String namespaceUri, final String localName) {
        return REASONS.get(localName);
    }
}
