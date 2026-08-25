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

package stroom.floormap.shared;

/**
 * What the object properties form will and will not accept in a number field.
 *
 * <p>Kept out of the view so it can be tested directly: every one of these
 * rules exists because breaking it wrote something unusable into the store.
 * A field that silently fell back to a default sent the object to 0 m, or reset
 * its scale to 1, as though that were what the user had asked for; a field that
 * accepted {@code NaN} or {@code Infinity} — both of which
 * {@link Double#parseDouble} parses quite happily — poisoned the placement
 * matrix, leaving the object undrawable, unselectable, and unreachable without
 * editing the store by hand.</p>
 */
public final class FloorMapFieldValidation {

    private FloorMapFieldValidation() {
        // Static utility.
    }

    /**
     * Checks one number field, naming it in any message so the user is told
     * which box to correct rather than being left to find it.
     *
     * @param text      what the box currently holds; {@code null} reads as empty
     * @param shownText the text the form last wrote into the box, or {@code null}
     *                  when the box has no such tracking. A box still holding
     *                  exactly this is untouched and always passes: the
     *                  displayed value is rounded for legibility and is not what
     *                  gets saved, so it is not the user's input to reject
     * @param label     the field's name, as it reads on the form
     * @param positive  whether the value must be greater than zero
     * @return the message to show, or {@code null} when the box is fit to save
     */
    public static String checkNumber(final String text,
                                     final String shownText,
                                     final String label,
                                     final boolean positive) {
        final String trimmed = text == null
                ? ""
                : text.trim();
        if (trimmed.equals(shownText)) {
            return null;
        }
        if (trimmed.isEmpty()) {
            // Deliberately not read as "leave unchanged" — an untouched box is
            // that, and clearing one is a far likelier slip than an intention.
            return label + " must not be empty. Enter a number.";
        }
        final double value;
        try {
            value = Double.parseDouble(trimmed);
        } catch (final NumberFormatException e) {
            return label + " must be a number.";
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return label + " must be a finite number.";
        }
        if (positive && value <= 0) {
            return label + " must be greater than zero.";
        }
        return null;
    }
}
