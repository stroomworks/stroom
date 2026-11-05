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

package stroom.egress;

import stroom.egress.api.EgressSink;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/// Fails the build if a class that can send data outside Stroom is not accounted for. A class that depends on one of
/// the network, mail, object-store, message-bus or search clients below must either carry `@EgressSink`, saying it
/// calls the egress guard, or be on the allow-list with a reason. A new sink therefore cannot be added without a
/// decision, and an allow-list entry for a class that no longer exists fails too, so the list cannot go stale.
///
/// The match is made on the types a class depends on, so an unrelated class with the same simple name (Hadoop
/// `Configuration`, jOOQ `Processor`) is never flagged.
class TestEgressSinks {

    private static final String ALLOW_LIST = "/stroom/egress/egress-sink-allowlist.txt";
    private static final String SAXON_ALLOW_LIST = "/stroom/egress/saxon-site-allowlist.txt";

    // Types that create a Saxon evaluator. A class that creates one must confine it, or be on the Saxon allow-list.
    private static final Set<String> SAXON_CREATION_TYPES = Set.of(
            "net.sf.saxon.s9api.Processor",
            "net.sf.saxon.Configuration",
            "net.sf.saxon.TransformerFactoryImpl",
            "javax.xml.transform.TransformerFactory");
    private static final String SAXON_CONFINEMENT = "stroom.util.xml.saxon.SaxonConfinement";

    // The client types that can reach a destination.
    private static final Set<String> IO_TYPES = Set.of(
            "java.net.HttpURLConnection",
            "java.net.http.HttpClient",
            "java.net.Socket",
            "org.apache.hc.client5.http.classic.HttpClient",
            "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
            "jakarta.ws.rs.client.Client",
            "jakarta.ws.rs.client.WebTarget",
            "org.simplejavamail.api.mailer.Mailer",
            "software.amazon.awssdk.services.s3.S3Client",
            "software.amazon.awssdk.services.s3.S3AsyncClient",
            "software.amazon.awssdk.services.sqs.SqsClient",
            "org.apache.kafka.clients.producer.KafkaProducer",
            "co.elastic.clients.elasticsearch.ElasticsearchClient",
            "org.apache.solr.client.solrj.SolrClient");

    @Test
    void testEverySinkIsAccountedFor() throws IOException {
        final Map<String, String> allowList = readAllowList();
        final Set<String> sinks = findClassesThatReachOutside();

        final List<String> unaccounted = new ArrayList<>();
        for (final String name : sinks) {
            if (!allowList.containsKey(name)) {
                unaccounted.add(name);
            }
        }
        assertThat(unaccounted)
                .as("Classes that can send data outside Stroom but neither declare @EgressSink nor are on %s. "
                    + "Call the egress guard and annotate the class, or add it to the allow-list with a reason.",
                        ALLOW_LIST)
                .isEmpty();

        final Set<String> stale = new TreeSet<>(allowList.keySet());
        stale.removeAll(sinks);
        assertThat(stale)
                .as("Allow-list entries for classes that no longer reach outside, or no longer exist: remove them")
                .isEmpty();
    }

    // A control for the detector itself: a class that is known to call an HTTP client must be seen to depend on one,
    // otherwise the scan above could pass because it found nothing at all.
    @Test
    void testTheDetectorSeesAKnownSink() {
        try (final ScanResult result = new ClassGraph()
                .enableClasspath()
                .enableNonSystemModules()
                .acceptPackages("stroom")
                .enableClassInfo()
                .ignoreClassVisibility()
                .enableInterClassDependencies()
                .enableExternalClasses()
                .scan()) {
            final ClassInfo httpCall = result.getClassInfo("stroom.pipeline.xsltfunctions.HttpCall");
            assertThat(httpCall).isNotNull();
            assertThat(httpCall.getClassDependencies().stream().map(ClassInfo::getName))
                    .anyMatch(IO_TYPES::contains);
        }
    }

    // A Saxon evaluator that is not confined can read files and URLs and write files, whatever the policy says.
    @Test
    void testEverySaxonSiteIsConfinedOrAllowed() throws IOException {
        final Map<String, String> allowList = readAllowList(SAXON_ALLOW_LIST);
        final Set<String> unconfined = new TreeSet<>();
        try (final ScanResult result = new ClassGraph()
                .enableClasspath()
                .enableNonSystemModules()
                .acceptPackages("stroom")
                .enableClassInfo()
                .ignoreClassVisibility()
                .enableInterClassDependencies()
                .enableExternalClasses()
                .scan()) {
            final Map<String, Boolean> confinedByClass = new LinkedHashMap<>();
            final Set<String> creators = new TreeSet<>();
            for (final ClassInfo classInfo : result.getAllClasses()) {
                if (classInfo.isExternalClass() || isTestClass(classInfo)) {
                    continue;
                }
                final String name = outerName(classInfo.getName());
                final List<String> dependencies = classInfo.getClassDependencies()
                        .stream()
                        .map(ClassInfo::getName)
                        .toList();
                if (dependencies.stream().anyMatch(SAXON_CREATION_TYPES::contains)) {
                    creators.add(name);
                }
                if (dependencies.contains(SAXON_CONFINEMENT)) {
                    confinedByClass.put(name, true);
                }
            }
            for (final String creator : creators) {
                if (!confinedByClass.containsKey(creator) && !allowList.containsKey(creator)) {
                    unconfined.add(creator);
                }
            }
            final Set<String> stale = new TreeSet<>(allowList.keySet());
            stale.removeAll(creators);
            assertThat(stale).as("Saxon allow-list entries that no longer create a Saxon evaluator").isEmpty();
        }
        assertThat(unconfined)
                .as("Classes that create a Saxon evaluator without calling SaxonConfinement and not on %s",
                        SAXON_ALLOW_LIST)
                .isEmpty();
    }

    @Test
    void testEveryAllowListEntryHasAReason() throws IOException {
        assertThat(readAllowList().entrySet())
                .allSatisfy(entry -> assertThat(entry.getValue()).isNotBlank());
        assertThat(readAllowList(SAXON_ALLOW_LIST).entrySet())
                .allSatisfy(entry -> assertThat(entry.getValue()).isNotBlank());
    }

    // Classes that depend on an I/O type and are not annotated.
    private static Set<String> findClassesThatReachOutside() {
        final Set<String> found = new TreeSet<>();
        try (final ScanResult result = new ClassGraph()
                .enableClasspath()
                .enableNonSystemModules()
                .acceptPackages("stroom")
                .enableClassInfo()
                .ignoreClassVisibility()
                .enableAnnotationInfo()
                .enableInterClassDependencies()
                .enableExternalClasses()
                .scan()) {
            final Set<String> annotated = new HashSet<>();
            result.getClassesWithAnnotation(EgressSink.class.getName())
                    .forEach(classInfo -> annotated.add(classInfo.getName()));
            for (final ClassInfo classInfo : result.getAllClasses()) {
                // Inner classes are accounted for through their outer class.
                if (classInfo.isExternalClass()) {
                    continue;
                }
                final String name = outerName(classInfo.getName());
                if (isTestClass(classInfo) || annotated.contains(name)) {
                    continue;
                }
                final boolean reaches = classInfo.getClassDependencies()
                        .stream()
                        .map(ClassInfo::getName)
                        .anyMatch(IO_TYPES::contains);
                if (reaches) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    private static String outerName(final String name) {
        final int dollar = name.indexOf('$');
        return dollar < 0
                ? name
                : name.substring(0, dollar);
    }

    private static boolean isTestClass(final ClassInfo classInfo) {
        final String location = String.valueOf(classInfo.getClasspathElementURI());
        return location.contains("classes/java/test") || location.contains("classes/java/testFixtures");
    }

    private static Map<String, String> readAllowList() throws IOException {
        return readAllowList(ALLOW_LIST);
    }

    private static Map<String, String> readAllowList(final String resource) throws IOException {
        final Map<String, String> entries = new LinkedHashMap<>();
        try (final InputStream in = TestEgressSinks.class.getResourceAsStream(resource)) {
            assertThat(in).as("allow-list resource %s", resource).isNotNull();
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                final int bar = trimmed.indexOf('|');
                final String name = bar < 0
                        ? trimmed
                        : trimmed.substring(0, bar).trim();
                final String reason = bar < 0
                        ? ""
                        : trimmed.substring(bar + 1).trim();
                entries.put(name, reason);
            }
        }
        return entries;
    }
}
