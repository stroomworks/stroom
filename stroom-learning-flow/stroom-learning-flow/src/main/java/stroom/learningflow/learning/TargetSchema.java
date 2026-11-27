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

package stroom.learningflow.learning;

import stroom.docref.DocRef;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;

/// The schema a Learning Flow's translations must produce, resolved from the `XMLSchema` document named by
/// `LearningFlowDoc.getTargetSchema()`. The prompts, the target checks and the schema conformance check all
/// read it, so that changing the setting changes all three together.
///
/// @param docRef       The `XMLSchema` document this came from.
/// @param name         Its name, for saying which schema a problem is about.
/// @param namespaceURI The namespace a translation's records are in, e.g. `event-logging:3`.
/// @param systemId     The id `xsi:schemaLocation` names, e.g. `file://event-logging-v4.1.0.xsd`, which
///                     is unique in Stroom and so picks out this version alone.
/// @param version      The version the XSD declares, e.g. `4.1.0`; null where it declares none.
/// @param rootElement  The element a document of this schema starts with, e.g. `Events`; null where the
///                     XSD declares no global element.
/// @param text         The XSD itself, for the summary of its structure that the prompt includes
///                     ([TargetStructure]) and for the content-model hints the conformance check gives.
public record TargetSchema(DocRef docRef,
                           String name,
                           String namespaceURI,
                           String systemId,
                           String version,
                           String rootElement,
                           String text) {

    /// The namespace every version of event-logging uses, from 3.0.0 to 4.1.0. The hand-written guidance
    /// for event-logging (the `EventDetail` branches, the *marked Unknown*, the "no `Data`" rule) applies
    /// only to a target in this namespace; every other schema is described from its XSD.
    public static final String EVENT_LOGGING = "event-logging:3";

    /// @return What `xsi:schemaLocation` says for this schema: the namespace, then the system id.
    public String schemaLocation() {
        return namespaceURI + " " + systemId;
    }

    /// @return Whether this is event-logging, which has guidance of its own that no other schema has.
    public boolean isEventLogging() {
        return EVENT_LOGGING.equals(namespaceURI);
    }

    /// Whether the guidance and checks written for event-logging apply to a target. They apply to
    /// event-logging, and also when there is no target at all, because a document saved before the target
    /// schema setting existed has none and was always asked for event-logging.
    ///
    /// @param target The target, or null for none.
    /// @return Whether it is event-logging, or none.
    public static boolean isEventLoggingOrNone(final TargetSchema target) {
        return target == null || target.isEventLogging();
    }

    /// Returns criteria that find this schema version alone, rather than every version of its group. A
    /// system id is unique in Stroom, so the namespace and system id together identify one document.
    ///
    /// @return The criteria.
    public FindXMLSchemaCriteria criteria() {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setNamespaceURI(namespaceURI);
        criteria.setSystemId(systemId);
        return criteria;
    }
}
