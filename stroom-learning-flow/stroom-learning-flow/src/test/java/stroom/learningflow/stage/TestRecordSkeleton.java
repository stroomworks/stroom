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

import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.shared.LearningFlowDoc;

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
        assertThat(LearningPrefix.learningPrefix("\n\n", LearningFlowDoc.builder().uuid("d").build())).isEmpty();
        assertThat(LearningPrefix.learningPrefix("only\n",
                LearningFlowDoc.builder().uuid("d").heldOutFraction(0.9).build()))
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
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").sampleSizeLimit(600).build();

        final String prefix = LearningPrefix.learningPrefix(document.toString(), doc);

        // The prefix is still a document within the limit: the root's whole start tag, whole children, and the
        // root closed.
        assertThat(prefix).startsWith("<log xmlns=\"urn:x\" v=\"a>b\">").endsWith("</log>");
        assertThat(prefix.length()).isLessThanOrEqualTo(600 + "</log>".length());
        assertThat(OutputRecords.parse(prefix)).isPresent();
        assertThat(OutputRecords.parse(prefix).orElseThrow().records()).hasSizeBetween(1, 39);
        // A document too small to cut is shown whole, and one whose first child alone exceeds the limit still
        // shows that child.
        assertThat(LearningPrefix.learningPrefix("<a><b/></a>", doc)).isEqualTo("<a><b/></a>");
        assertThat(OutputRecords.parse(LearningPrefix.learningPrefix(document.toString(),
                LearningFlowDoc.builder().uuid("d").sampleSizeLimit(10).build())).orElseThrow().records())
                .hasSize(1);
    }

    @Test
    void textThatLooksLikeMarkupButIsNotADocumentIsCutByWholeLines() {
        final String chat = "<alice> hello there everyone\n<bob> hi alice\n<carol> morning all\n";
        assertThat(RecordSkeleton.isMarkup(chat)).isTrue();
        assertThat(LearningPrefix.learningPrefix(chat,
                LearningFlowDoc.builder().uuid("d").sampleSizeLimit(45).build()))
                .isEqualTo("<alice> hello there everyone\n<bob> hi alice\n");
    }

    @Test
    void aRecordsKindIsItsFieldsNotItsValuesNorItsArrayLengths() {
        // Named Data elements repeat among their siblings, so their Name attributes are field names and tell a
        // logon from a process creation. A lone User's Name is a value, so each user is not a separate kind.
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
        // A JSON key always names a field, and an array is one kind however many items it holds.
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
        assertThat(LearningPrefix.isJsonDocument("{\n  \"events\": [\n    {\"a\": 1}\n  ]\n}\n")).isTrue();
        assertThat(LearningPrefix.isJsonDocument("[\n  {\"a\": 1},\n  {\"a\": 2}\n]\n")).isTrue();
        assertThat(LearningPrefix.isJsonDocument("{\"a\": 1}\n{\"a\": 2}\n")).describedAs("JSON lines").isFalse();
        final String apache = "[Mon Sep 21 10:00:00 2026] [error] [client 10.0.0.1] File does not exist\n"
                              + "[Mon Sep 21 10:00:01 2026] [error] [client 10.0.0.2] File does not exist\n";
        assertThat(LearningPrefix.isJsonDocument(apache)).isFalse();
        assertThat(LearningPrefix.isJsonDocument("[1.2.3.4] up\n[1.2.3.5] down\n")).isFalse();
        assertThat(LearningPrefix.isJsonDocument("{\n\"a\": 1}\n{\"b\": 2}\n")).describedAs("two values").isFalse();
        // A bracketed log is cut by lines and has records held out like any text, rather than being shown whole.
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").heldOutFraction(0.5).sampleSizeLimit(1000)
                .build();
        assertThat(LearningPrefix.learningPrefix(apache, doc))
                .isEqualTo(apache.lines().findFirst().orElseThrow() + "\n");
    }

    @Test
    void markupThatIsNotOneDocumentIsHeldOutLikeText() {
        // One fragment per line and no root: it looks like markup from its first character, but is not one
        // document, so the held-out fraction applies as it does to any text. The model must not see the records
        // it is judged on.
        final String fragments = "<Event id=\"1\"/>\n<Event id=\"2\"/>\n<Event id=\"3\"/>\n<Event id=\"4\"/>\n";
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").heldOutFraction(0.5).sampleSizeLimit(1000)
                .build();
        assertThat(LearningPrefix.learningPrefix(fragments, doc)).isEqualTo("<Event id=\"1\"/>\n<Event id=\"2\"/>\n");
        final LearningFlowDoc small = LearningFlowDoc.builder().uuid("d").heldOutFraction(0.5).sampleSizeLimit(20)
                .build();
        assertThat(LearningPrefix.learningPrefix(fragments, small)).isEqualTo("<Event id=\"1\"/>\n");
    }

    @Test
    void aStreamBringsItsRecordsAsTheYieldScorerCountsThem() {
        // A document brings its root's children. Fragments with no root bring their lines, not zero, or the
        // stream would be judged as having brought no records. A JSON document is one value, and text brings
        // its non-blank lines.
        assertThat(LearningPrefix.recordsBrought(THREE_RECORDS)).isEqualTo(3);
        assertThat(LearningPrefix.recordsBrought("<Event id=\"1\"/>\n<Event id=\"2\"/>\n<Event id=\"3\"/>\n"))
                .isEqualTo(3);
        assertThat(LearningPrefix.recordsBrought("{\n  \"events\": [\n    {\"a\": 1},\n    {\"a\": 2}\n  ]\n}\n"))
                .isEqualTo(1);
        assertThat(LearningPrefix.recordsBrought("{\"a\": 1}\n{\"a\": 2}\n")).isEqualTo(2);
        assertThat(LearningPrefix.recordsBrought("one\n\ntwo\n")).isEqualTo(2);
    }

    @Test
    void aByteOrderMarkIsNotContent() {
        // If a BOM were treated as content, XML and JSON would look like plain text to every classification.
        assertThat(RecordSkeleton.isMarkup("\uFEFF<records/>")).isTrue();
        assertThat(RecordSkeleton.recordSkeleton(RecordSkeleton.withoutBom("\uFEFF" + ONE_RECORD)))
                .isEqualTo(RecordSkeleton.recordSkeleton(ONE_RECORD));
        assertThat(LearningPrefix.isJsonDocument("\uFEFF{\n  \"a\": 1\n}\n")).isTrue();
        assertThat(RecordSkeleton.withoutBom("\uFEFFx")).isEqualTo("x");
        assertThat(RecordSkeleton.withoutBom("")).isEmpty();
    }

    @Test
    void aJsonDocumentOverTheSampleSizeLimitIsCutAtItsArraysItems() {
        // Cut by lines, the sample would not parse, and the split question would abandon the attempt on a
        // sample the parser cannot read. That would happen for every JSON feed over the limit.
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
        assertThat(LearningPrefix.learningPrefix(whole, LearningFlowDoc.builder().uuid("d")
                .sampleSizeLimit(10_000).heldOutFraction(0.0).build()))
                .describedAs("within the limit and nothing held out, whole").isEqualTo(whole);

        // A document within the limit still has its held-out fraction withheld. Otherwise, for every JSON feed
        // that fitted, promotion would be judged on exactly what the model had been shown, rather than on
        // records it never saw.
        final String held = LearningPrefix.learningPrefix(whole, LearningFlowDoc.builder().uuid("d")
                .sampleSizeLimit(10_000).heldOutFraction(0.2).build());

        assertThat(held).describedAs("a fifth of the items held back").doesNotContain("user32");
        assertThat(held).contains("user31");
        assertThat(LearningPrefix.isJsonDocument(held)).describedAs("and still one value the parser can read").isTrue();

        final String cut = LearningPrefix.learningPrefix(whole,
                LearningFlowDoc.builder().uuid("d").sampleSizeLimit(500).build());

        assertThat(cut.length()).isLessThanOrEqualTo(500);
        assertThat(cut).describedAs("still one document").startsWith("{").endsWith("}\n");
        assertThat(LearningPrefix.isJsonDocument(cut)).describedAs("and still one value the parser can read").isTrue();
        assertThat(cut).contains("\"source\": \"api\"").contains("\"events\": [").contains("user0");
        assertThat(cut).describedAs("cut at an item, not mid-item").doesNotContain("user39");
        // A document with no array to cut is shown whole rather than truncated into nonsense.
        final String noArray = "{\n  \"a\": \"" + "x".repeat(200) + "\"\n}\n";
        assertThat(LearningPrefix.learningPrefix(noArray,
                LearningFlowDoc.builder().uuid("d").sampleSizeLimit(50).build()))
                .isEqualTo(noArray);
    }

    @Test
    void textOverTheSampleSizeLimitIsCutByWholeLines() {
        final String lines = "one,1\ntwo,2\nthree,3\nfour,4\n";
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").heldOutFraction(0.0).sampleSizeLimit(14)
                .build();
        assertThat(LearningPrefix.learningPrefix(lines, doc)).isEqualTo("one,1\ntwo,2\n");
        assertThat(LearningPrefix.learningPrefix(lines, LearningFlowDoc.builder().uuid("d").heldOutFraction(0.0)
                .sampleSizeLimit(2).build())).describedAs("one line is always shown").isEqualTo("one,1\n");
    }
}
