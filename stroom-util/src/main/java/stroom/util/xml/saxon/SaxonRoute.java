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

package stroom.util.xml.saxon;

/// The standard XSLT and XPath routes by which a stylesheet can reach outside its input. Saxon gives each its own
/// hook, and no hook covers another's routes.
public enum SaxonRoute {
    /// `doc()`, `document()`, `doc-available()` and `xsl:source-document`. These can fetch a URL or read a file.
    DOCUMENT(false),
    /// `unparsed-text()`, `unparsed-text-lines()`, `unparsed-text-available()` and `json-doc()`. Denied outright.
    UNPARSED_TEXT(true),
    /// `collection()` and `uri-collection()`. Denied outright.
    COLLECTION(true),
    /// `xsl:result-document`. Denied outright.
    RESULT_DOCUMENT(true);

    private final boolean deniedOutright;

    SaxonRoute(final boolean deniedOutright) {
        this.deniedOutright = deniedOutright;
    }

    /// Whether no rule can ever permit this route.
    ///
    /// @return True if the route is denied in every mode.
    public boolean isDeniedOutright() {
        return deniedOutright;
    }
}
