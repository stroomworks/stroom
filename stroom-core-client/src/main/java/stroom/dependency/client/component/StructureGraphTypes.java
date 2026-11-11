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

package stroom.dependency.client.component;

import java.util.HashMap;
import java.util.Map;

/// How each document type is drawn in the Structure view: the letter in its glyph, its name, and the
/// modifier that gives its glyph a colour.
///
/// The colours themselves are theme tokens in `structure-graph.css`, keyed by the modifier.
public final class StructureGraphTypes {

    private static final Map<String, TypeStyle> STYLES = new HashMap<>();
    private static final String OTHER_MODIFIER = "other";

    static {
        add("Feed", "F", "Feed", "feed");
        add("Pipeline", "P", "Pipeline", "pipeline");
        add("XSLT", "X", "XSLT", "xslt");
        add("XMLSchema", "S", "XML schema", "xml-schema");
        add("TextConverter", "T", "Text converter", "text-converter");
        add("Index", "I", "Lucene index", "index");
        add("ElasticIndex", "E", "Elastic index", "elastic-index");
        add("SolrIndex", "R", "Solr index", "solr-index");
        add("StatisticStore", "Σ", "Statistic store", "statistic-store");
        add("StroomStatsStore", "Σ", "Stroom-stats store", "stroom-stats-store");
        add("AnalyticRule", "A", "Analytic rule", "analytic-rule");
        add("PlanB", "B", "Plan B store", "plan-b");
        add("Dashboard", "D", "Dashboard", "dashboard");
        add("Dictionary", "W", "Dictionary", "dictionary");
        add("Endpoint", "↗", "Outside Stroom", "endpoint");
    }

    private StructureGraphTypes() {
        // Static methods only.
    }

    /// @param type A document type, e.g. `Feed`.
    /// @return the character drawn in the type's glyph: a letter, or the first letter of an unknown type.
    public static String glyph(final String type) {
        final TypeStyle style = STYLES.get(type);
        if (style != null) {
            return style.glyph();
        }
        return type == null || type.isEmpty()
                ? "?"
                : type.substring(0, 1).toUpperCase();
    }

    /// @param type A document type, e.g. `XMLSchema`.
    /// @return the type's name for people, e.g. `XML schema`; the type itself if unknown.
    public static String label(final String type) {
        final TypeStyle style = STYLES.get(type);
        if (style != null) {
            return style.label();
        }
        return type == null
                ? ""
                : type;
    }

    /// @param type A document type, e.g. `PlanB`.
    /// @return the BEM modifier for the type's colour, e.g. `plan-b`; `other` if unknown.
    public static String modifier(final String type) {
        final TypeStyle style = STYLES.get(type);
        return style == null
                ? OTHER_MODIFIER
                : style.modifier();
    }

    private static void add(final String type, final String glyph, final String label, final String modifier) {
        STYLES.put(type, new TypeStyle(glyph, label, modifier));
    }

    private record TypeStyle(String glyph, String label, String modifier) {

    }
}
