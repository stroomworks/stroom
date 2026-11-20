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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
import stroom.util.shared.NullSafe;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/// The words a run was asked with, recorded against the attempt (decision N9, option D extended).
///
/// A turn records what it asked, not the prompt it sent (A146: a prompt holds the sample, and the
/// sample is the stream's own text under the stream's retention, not the attempt's). So to show a
/// person what the model was actually sent — or to carry a parked run on in the words it began in —
/// what the prompt was *built from* has to be kept.
///
/// What is kept is the resolved template set: every [Template] with the text this run used, the
/// Learning Flow's own where it overrode one and the built-in otherwise. The text rather than only the
/// version, so that a prompt can be rebuilt after an upgrade without the old templates still being in
/// the code.
///
/// **What is deliberately not kept here is the guidance.** A supervisor's notes are read afresh before
/// every question ([Stage] guides each one with `guidance.standing(learner)`), precisely so that a hint
/// given while an attempt was parked reaches the turn that resumes it. They are rows of their own, with
/// the time they were given, so what was standing at a turn is read from them rather than frozen here —
/// a copy taken when the run opened would be wrong for every turn after the first.
///
/// None of this is stream text: the sample is identified by the run's stream and is not copied.
///
/// @param builtInVersion The built-in template version the run was asked against ([Templates#VERSION]).
/// @param templates      Every template with the text this run used.
/// @param instructions   The Learning Flow's *Instructions to the AI*, as the run had them.
/// @param build          The build of Stroom that ran it, so that a rebuild after an upgrade can say the
///                       checks' wording may have moved under it.
public record AskedWith(int builtInVersion,
                        Map<Template, String> templates,
                        String instructions,
                        String build) {

    /// A run whose words were not recorded: one opened before they were kept. Rebuilding its prompt is
    /// refused rather than guessed at, and resuming it falls back to the Learning Flow as it stands.
    public static final AskedWith UNKNOWN = new AskedWith(0, Map.of(), null, null);

    public AskedWith {
        templates = templates == null
                ? Map.of()
                : Map.copyOf(templates);
    }

    /// The words a document would ask with now: what is recorded when an attempt opens, and again when a
    /// parked one resumes.
    ///
    /// @param doc   The document.
    /// @param build The build of Stroom running it.
    /// @return What it would be asked with.
    public static AskedWith of(final ShapeshifterAiDoc doc, final String build) {
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

    /// Whether anything was recorded. A run opened before N9 has nothing to resume from or rebuild, and
    /// says so rather than being shown the words that stand today as though they were the words it used.
    ///
    /// @return True where the words are known.
    public boolean known() {
        return !templates.isEmpty();
    }

    /// The template set as the run used it, for building a [QuestionText] that asks in the run's own
    /// words rather than the document's current ones.
    ///
    /// @return The templates, or empty where the run predates them being kept.
    public Templates asTemplates() {
        return Templates.ofRecorded(templates);
    }

    /// Whether the built-in templates have moved since the run was asked, which is what makes a rebuilt
    /// prompt worth a caveat: the checks' wording travels with the built-ins.
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
