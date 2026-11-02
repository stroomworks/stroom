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

import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestRecordSkeleton {

    private static final String ONE_RECORD = """
            <?xml version="1.0"?>
            <records xmlns="records:2">
              <record><data name="user" value="jb"/><data name="door" value="7"/></record>
            </records>
            """;
    private static final String THREE_RECORDS = """
            <records xmlns="records:2">
              <record><data name="user" value="ab"/><data name="door" value="1"/></record>
              <record><data name="user" value="cd"/><data name="door" value="2"/></record>
              <record><data name="user" value="ef"/><data name="door" value="3"/></record>
            </records>
            """;

    @Test
    void aBlankStreamHasAnEmptyLearningPrefix() {
        assertThat(Stage.learningPrefix("\n\n", ShapeshifterAiDoc.builder().uuid("d").build())).isEmpty();
        assertThat(Stage.learningPrefix("only\n", ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.9).build()))
                .describedAs("at least one line is kept when there is one")
                .isEqualTo("only\n");
    }

    @Test
    void markupOverTheSampleSizeLimitIsCutAtARecordBoundary() {
        final StringBuilder document = new StringBuilder("<?xml version=\"1.0\"?>\n<log xmlns=\"urn:x\" v=\"a>b\">\n");
        for (int i = 0; i < 40; i++) {
            document.append("  <entry id=\"").append(i).append("\"><who>user").append(i).append("</who></entry>\n");
        }
        document.append("</log>\n");
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(600).build();

        final String prefix = Stage.learningPrefix(document.toString(), doc);

        // Still a document — the root's start tag whole, whole children, the root closed — within the limit.
        assertThat(prefix).startsWith("<log xmlns=\"urn:x\" v=\"a>b\">").endsWith("</log>");
        assertThat(prefix.length()).isLessThanOrEqualTo(600 + "</log>".length());
        assertThat(OutputRecords.parse(prefix)).isPresent();
        assertThat(OutputRecords.parse(prefix).orElseThrow().records()).hasSizeBetween(1, 39);
        // A document too small to cut is shown whole; one whose first child alone exceeds the limit still shows it.
        assertThat(Stage.learningPrefix("<a><b/></a>", doc)).isEqualTo("<a><b/></a>");
        assertThat(OutputRecords.parse(Stage.learningPrefix(document.toString(),
                ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(10).build())).orElseThrow().records())
                .hasSize(1);
    }

    @Test
    void textThatLooksLikeMarkupButIsNotADocumentIsCutByWholeLines() {
        final String chat = "<alice> hello there everyone\n<bob> hi alice\n<carol> morning all\n";
        assertThat(RecordSkeleton.isMarkup(chat)).isTrue();
        assertThat(Stage.learningPrefix(chat, ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(45).build()))
                .isEqualTo("<alice> hello there everyone\n<bob> hi alice\n");
    }

    @Test
    void aRecordsKindIsItsFieldsNotItsValuesNorItsArrayLengths() {
        // Named Data repeat among their siblings, so their Names are the fields and tell a logon from a
        // process creation; a lone User's Name is a value and does not make every user a kind.
        final String logon = "<Event><System><EventID>4624</EventID></System><EventData>"
                             + "<Data Name=\"TargetUserName\">alice</Data><Data Name=\"LogonType\">2</Data>"
                             + "</EventData><User Name=\"alice\"/></Event>";
        final String otherLogon = logon.replace("alice", "bob");
        final String process = "<Event><System><EventID>4688</EventID></System><EventData>"
                               + "<Data Name=\"NewProcessName\">cmd.exe</Data><Data Name=\"LogonType\">2</Data>"
                               + "</EventData><User Name=\"carol\"/></Event>";
        assertThat(RecordSkeleton.recordSkeleton(logon)).isEqualTo(RecordSkeleton.recordSkeleton(otherLogon));
        assertThat(RecordSkeleton.recordSkeleton(logon)).isNotEqualTo(RecordSkeleton.recordSkeleton(process));
        assertThat(RecordSkeleton.recordSkeleton(logon))
                .contains("<Data Name=TargetUserName>", "<User Name>")
                .doesNotContain("alice");
        // A JSON key always names a field; an array is one kind however many items it holds.
        final String twoRoles = "<map xmlns=\"http://www.w3.org/2013/XSL/json\"><string key=\"user\">a</string>"
                                + "<array key=\"roles\"><string>x</string><string>y</string></array></map>";
        final String threeRoles = twoRoles.replace("<string>y</string>", "<string>y</string><string>z</string>");
        final String noRoles = twoRoles.replace("<array key=\"roles\">", "<array key=\"groups\">");
        assertThat(RecordSkeleton.recordSkeleton(twoRoles)).isEqualTo(RecordSkeleton.recordSkeleton(threeRoles));
        assertThat(RecordSkeleton.recordSkeleton(twoRoles)).isNotEqualTo(RecordSkeleton.recordSkeleton(noRoles));
        assertThat(RecordSkeleton.recordSkeleton(twoRoles))
                .isEqualTo("<map><string key=user></string><array key=roles><string></string></array></map>");
        // What does not parse falls back to the text skeleton rather than throwing.
        assertThat(RecordSkeleton.recordSkeleton("<a><b></a>")).isEqualTo(RecordSkeleton.textSkeleton("<a><b></a>"));
    }

    @Test
    void aJsonDocumentIsOneValueOverLinesAndABracketedLogIsNot() {
        assertThat(Stage.isJsonDocument("{\n  \"events\": [\n    {\"a\": 1}\n  ]\n}\n")).isTrue();
        assertThat(Stage.isJsonDocument("[\n  {\"a\": 1},\n  {\"a\": 2}\n]\n")).isTrue();
        assertThat(Stage.isJsonDocument("{\"a\": 1}\n{\"a\": 2}\n")).describedAs("JSON lines").isFalse();
        final String apache = "[Mon Sep 21 10:00:00 2026] [error] [client 10.0.0.1] File does not exist\n"
                              + "[Mon Sep 21 10:00:01 2026] [error] [client 10.0.0.2] File does not exist\n";
        assertThat(Stage.isJsonDocument(apache)).isFalse();
        assertThat(Stage.isJsonDocument("[1.2.3.4] up\n[1.2.3.5] down\n")).isFalse();
        assertThat(Stage.isJsonDocument("{\n\"a\": 1}\n{\"b\": 2}\n")).describedAs("two values").isFalse();
        // A bracketed log is cut by lines and held out like any text, not learned whole.
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.5).sampleSizeLimit(1000)
                .build();
        assertThat(Stage.learningPrefix(apache, doc)).isEqualTo(apache.lines().findFirst().orElseThrow() + "\n");
    }

    @Test
    void markupThatIsNotOneDocumentIsHeldOutLikeText() {
        // One fragment per line and no root: markup by its first character, but not a document, so the
        // held-out fraction applies as it does to any text — the model must not see what it is judged on.
        final String fragments = "<Event id=\"1\"/>\n<Event id=\"2\"/>\n<Event id=\"3\"/>\n<Event id=\"4\"/>\n";
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.5).sampleSizeLimit(1000)
                .build();
        assertThat(Stage.learningPrefix(fragments, doc)).isEqualTo("<Event id=\"1\"/>\n<Event id=\"2\"/>\n");
        final ShapeshifterAiDoc small = ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.5).sampleSizeLimit(20)
                .build();
        assertThat(Stage.learningPrefix(fragments, small)).isEqualTo("<Event id=\"1\"/>\n");
    }

    @Test
    void aStreamBringsItsRecordsAsTheYieldScorerCountsThem() {
        // A document brings its root's children; fragments with no root bring their lines, not zero, or the
        // learner would be bound provisionally and stay so; a JSON document is one value; text is its lines.
        assertThat(Stage.recordsBrought(THREE_RECORDS)).isEqualTo(3);
        assertThat(Stage.recordsBrought("<Event id=\"1\"/>\n<Event id=\"2\"/>\n<Event id=\"3\"/>\n")).isEqualTo(3);
        assertThat(Stage.recordsBrought("{\n  \"events\": [\n    {\"a\": 1},\n    {\"a\": 2}\n  ]\n}\n")).isEqualTo(1);
        assertThat(Stage.recordsBrought("{\"a\": 1}\n{\"a\": 2}\n")).isEqualTo(2);
        assertThat(Stage.recordsBrought("one\n\ntwo\n")).isEqualTo(2);
    }

    @Test
    void aByteOrderMarkIsNotContent() {
        // Read as text, a BOM would make XML and JSON look like a line of text to every classification.
        assertThat(RecordSkeleton.isMarkup("\uFEFF<records/>")).isTrue();
        assertThat(RecordSkeleton.recordSkeleton(RecordSkeleton.withoutBom("\uFEFF" + ONE_RECORD)))
                .isEqualTo(RecordSkeleton.recordSkeleton(ONE_RECORD));
        assertThat(Stage.isJsonDocument("\uFEFF{\n  \"a\": 1\n}\n")).isTrue();
        assertThat(RecordSkeleton.withoutBom("\uFEFFx")).isEqualTo("x");
        assertThat(RecordSkeleton.withoutBom("")).isEmpty();
    }

    @Test
    void aJsonDocumentOverTheSampleSizeLimitIsCutAtItsArraysItems() {
        // Cut by lines it would not parse, and the split question would abandon the attempt on a sample the
        // parser cannot read — which is every JSON feed over the limit.
        final StringBuilder document = new StringBuilder("{\n  \"source\": \"api\",\n  \"events\": [\n");
        for (int i = 0; i < 40; i++) {
            document.append("    {\"time\": \"2026-09-22T09:00:0").append(i % 10)
                    .append("Z\", \"user\": \"user").append(i).append("\"}")
                    .append(i < 39
                            ? ",\n"
                            : "\n");
        }
        document.append("  ]\n}\n");
        final String whole = document.toString();
        assertThat(Stage.learningPrefix(whole, ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(10_000).build()))
                .describedAs("within the limit, whole").isEqualTo(whole);

        final String cut = Stage.learningPrefix(whole,
                ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(500).build());

        assertThat(cut.length()).isLessThanOrEqualTo(500);
        assertThat(cut).describedAs("still one document").startsWith("{").endsWith("}\n");
        assertThat(Stage.isJsonDocument(cut)).describedAs("and still one value the parser can read").isTrue();
        assertThat(cut).contains("\"source\": \"api\"").contains("\"events\": [").contains("user0");
        assertThat(cut).describedAs("cut at an item, not mid-item").doesNotContain("user39");
        // A document with no array to cut is shown whole rather than truncated into nonsense.
        final String noArray = "{\n  \"a\": \"" + "x".repeat(200) + "\"\n}\n";
        assertThat(Stage.learningPrefix(noArray, ShapeshifterAiDoc.builder().uuid("d").sampleSizeLimit(50).build()))
                .isEqualTo(noArray);
    }

    @Test
    void textOverTheSampleSizeLimitIsCutByWholeLines() {
        final String lines = "one,1\ntwo,2\nthree,3\nfour,4\n";
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.0).sampleSizeLimit(14)
                .build();
        assertThat(Stage.learningPrefix(lines, doc)).isEqualTo("one,1\ntwo,2\n");
        assertThat(Stage.learningPrefix(lines, ShapeshifterAiDoc.builder().uuid("d").heldOutFraction(0.0)
                .sampleSizeLimit(2).build())).describedAs("one line is always shown").isEqualTo("one,1\n");
    }
}
