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

/// What a learned stylesheet may use (decision N1, option C): an allow-list over every function and
/// instruction, never a list of banned names, so that a route nobody thought of is refused rather than
/// allowed.
///
/// A learned stylesheet runs in the real `XSLTFilter`, where Saxon has no resolver stopping it reading the
/// node's files, listing directories, making outbound requests or writing files — and several `stroom:`
/// functions call out, read other feeds' data, drop records or give a different answer each run. On an
/// automatic Learning Flow nobody reads the stylesheet before it runs, so the only guard is this list,
/// applied when a candidate is judged and again when it is promoted.
///
/// The list is in code to begin with ([#hardCoded()]). Track 2 step 6 pulls in the egress branch and moves
/// it onto the Egress Policy screen, where an administrator maintains it and it is enforced at run time as
/// well; this interface is what lets that happen without touching the callers.
public interface AiXsltAllowList {

    /// The XSLT namespace, whose elements are instructions and declarations rather than output.
    String XSLT_NAMESPACE = "http://www.w3.org/1999/XSL/Transform";

    /// The namespace of the standard XPath and XSLT function library, which a call writes without a prefix.
    String FUNCTIONS_NAMESPACE = "http://www.w3.org/2005/xpath-functions";

    /// The namespace of Stroom's own XSLT extension functions.
    String STROOM_NAMESPACE = "stroom";

    /// The XML Schema namespace, whose names are the constructor functions `xs:integer(…)` and the like.
    String SCHEMA_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    /// @return The list as this release has it, in code.
    static AiXsltAllowList hardCoded() {
        return HardCodedXsltAllowList.INSTANCE;
    }

    /// Whether a stylesheet may use an element of the XSLT namespace.
    ///
    /// @param localName The element's local name, e.g. `value-of`.
    /// @return Whether it is allowed.
    boolean allowsInstruction(String localName);

    /// Whether a stylesheet may call a function.
    ///
    /// @param namespaceUri The function's namespace; [#FUNCTIONS_NAMESPACE] for an unprefixed call.
    /// @param localName    The function's local name, e.g. `substring`.
    /// @return Whether it is allowed.
    boolean allowsFunction(String namespaceUri, String localName);

    /// Why a name is not allowed, for the reason fed back to the model.
    ///
    /// @param namespaceUri The namespace, or null for an instruction.
    /// @param localName    The local name.
    /// @return A sentence saying why, or null where there is nothing to add beyond its not being allowed.
    String reasonAgainst(String namespaceUri, String localName);
}
