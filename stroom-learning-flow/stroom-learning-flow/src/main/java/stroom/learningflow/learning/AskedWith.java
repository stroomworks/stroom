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

import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.Template;
import stroom.util.shared.NullSafe;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/// The templates and instructions a run's questions were worded with, recorded against the attempt.
///
/// A turn records what it asked, not the full prompt it sent, because the prompt contains the sample, and
/// the sample is the stream's text, which belongs under the stream's retention rather than the attempt's.
/// So to show a person what the model was actually sent, or to resume a parked run in the wording it began
/// with, what the prompt was *built from* has to be kept.
///
/// What is kept is the resolved template set: every [Template] with the text this run used, which is the
/// Learning Flow's own text where it overrides a template and the built-in text otherwise. The text is kept,
/// not just the version, so that a prompt can be rebuilt after an upgrade has changed the built-in templates.
///
/// **The guidance is deliberately not kept here.** A person's guidance is read afresh before every question
/// (`Learning` passes `guidance.standing(learner)` to the conversation), so that a hint given while an
/// attempt was parked reaches the turn that resumes it. Guidance is stored in its own rows with the time it
/// was given, so what was in force at a turn is read from them. A copy taken when the run opened would be
/// wrong for every turn after the first.
///
/// None of this is stream text: the sample is identified by the run's stream and is not copied.
///
/// @param builtInVersion The built-in template version the run was asked against ([Templates#VERSION]).
/// @param templates      Every template with the text this run used.
/// @param instructions   The Learning Flow's *Instructions to the AI*, as the run had them.
/// @param build          The build of Stroom that ran it, so that a prompt rebuilt after an upgrade can
///                       warn that the wording of the checks may have changed since.
public record AskedWith(int builtInVersion,
                        Map<Template, String> templates,
                        String instructions,
                        String build) {

    /// A run whose wording was not recorded, because it was opened by a version that did not record it.
    /// Rebuilding its prompt is refused rather than guessed at, and resuming it uses the Learning Flow as it
    /// is now.
    public static final AskedWith UNKNOWN = new AskedWith(0, Map.of(), null, null);

    public AskedWith {
        templates = templates == null
                ? Map.of()
                : Map.copyOf(templates);
    }

    /// Returns the wording a document would ask with now. This is recorded when an attempt opens, and again
    /// when a parked one resumes.
    ///
    /// @param doc   The document.
    /// @param build The build of Stroom running it.
    /// @return The resolved templates, instructions and build.
    public static AskedWith of(final LearningFlowDoc doc, final String build) {
        final Templates templates = Templates.of(doc.getFlow());
        final Map<Template, String> resolved = new EnumMap<>(Template.class);
        for (final Template template : Template.values()) {
            final String text = templates.text(template);
            if (text != null) {
                resolved.put(template, text);
            }
        }
        return new AskedWith(Templates.VERSION, resolved, doc.getInstructions(), build);
    }

    /// Whether the wording was recorded. A run opened by a version that did not record it has nothing to
    /// rebuild its prompts from, and says so rather than showing today's wording as though it were what the
    /// run used.
    ///
    /// @return True where the words are known.
    public boolean known() {
        return !templates.isEmpty();
    }

    /// Returns the template set as the run used it, for building a [QuestionText] that asks in the run's own
    /// wording rather than the document's current wording.
    ///
    /// @return The templates, or an empty set if the run's wording was not recorded.
    public Templates asTemplates() {
        return Templates.ofRecorded(templates);
    }

    /// Whether the built-in templates have changed since the run was asked. If so, a rebuilt prompt needs a
    /// warning, because the wording of the checks comes from the built-in templates.
    ///
    /// @return True where this run was asked against an older built-in version.
    public boolean olderThanBuiltIn() {
        return known() && builtInVersion != Templates.VERSION;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final AskedWith that)) {
            return false;
        }
        return builtInVersion == that.builtInVersion
               && templates.equals(that.templates)
               && Objects.equals(instructions, that.instructions)
               && Objects.equals(build, that.build);
    }

    @Override
    public int hashCode() {
        return Objects.hash(builtInVersion, templates, instructions, build);
    }

    @Override
    public String toString() {
        return "AskedWith{builtInVersion=" + builtInVersion
               + ", templates=" + templates.size()
               + ", instructions=" + NullSafe.getOrElse(instructions, text -> text.length() + " chars", "none")
               + ", build=" + build
               + '}';
    }
}
