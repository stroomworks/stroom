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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapFieldValidation {

    @Test
    void acceptsOrdinaryNumbers() {
        assertThat(FloorMapFieldValidation.checkNumber("12.5", null, "Position X", false)).isNull();
        assertThat(FloorMapFieldValidation.checkNumber("-3", null, "Position Y", false)).isNull();
        assertThat(FloorMapFieldValidation.checkNumber("0", null, "Rotation", false)).isNull();
        // Surrounding whitespace is a typing artefact, not an error.
        assertThat(FloorMapFieldValidation.checkNumber("  7  ", null, "Width", true)).isNull();
    }

    @Test
    void namesTheFieldItIsComplainingAbout() {
        // The whole point of the message: the user is told which box to fix.
        assertThat(FloorMapFieldValidation.checkNumber("abc", null, "Position X", false))
                .isEqualTo("Position X must be a number.");
        assertThat(FloorMapFieldValidation.checkNumber("abc", null, "Rotation", false))
                .isEqualTo("Rotation must be a number.");
        assertThat(FloorMapFieldValidation.checkNumber("abc", null, "Scale Y", true))
                .isEqualTo("Scale Y must be a number.");
    }

    @Test
    void rejectsBlank() {
        // A cleared box is not "leave unchanged" — an untouched box is that.
        assertThat(FloorMapFieldValidation.checkNumber("", null, "Position X", false))
                .isEqualTo("Position X must not be empty. Enter a number.");
        assertThat(FloorMapFieldValidation.checkNumber("   ", null, "Position X", false))
                .isEqualTo("Position X must not be empty. Enter a number.");
        assertThat(FloorMapFieldValidation.checkNumber(null, null, "Position X", false))
                .isEqualTo("Position X must not be empty. Enter a number.");
    }

    @Test
    void rejectsNanAndInfinity() {
        // Double.parseDouble takes both of these without complaint, and either
        // one poisons the placement matrix: the object is then neither drawn nor
        // selectable, with no way back short of editing the store by hand.
        assertThat(FloorMapFieldValidation.checkNumber("NaN", null, "Position X", false))
                .isEqualTo("Position X must be a finite number.");
        assertThat(FloorMapFieldValidation.checkNumber("Infinity", null, "Position Y", false))
                .isEqualTo("Position Y must be a finite number.");
        assertThat(FloorMapFieldValidation.checkNumber("-Infinity", null, "Rotation", false))
                .isEqualTo("Rotation must be a finite number.");
    }

    @Test
    void rejectsNonPositiveOnlyWhereSizeIsMeant() {
        // A size or scale of zero collapses the matrix; a position of zero is
        // just the origin.
        assertThat(FloorMapFieldValidation.checkNumber("0", null, "Width", true))
                .isEqualTo("Width must be greater than zero.");
        assertThat(FloorMapFieldValidation.checkNumber("-2", null, "Scale X", true))
                .isEqualTo("Scale X must be greater than zero.");
        assertThat(FloorMapFieldValidation.checkNumber("0", null, "Position X", false)).isNull();
        assertThat(FloorMapFieldValidation.checkNumber("-2", null, "Position X", false)).isNull();
    }

    @Test
    void passesOverAnUntouchedBox() {
        // The box shows a rounded value that is not what gets saved, so while it
        // still holds exactly what was written into it there is nothing of the
        // user's to reject — pressing OK on an untouched dialog must not fail.
        assertThat(FloorMapFieldValidation.checkNumber("1.23", "1.23", "Position X", false)).isNull();
        assertThat(FloorMapFieldValidation.checkNumber(" 1.23 ", "1.23", "Position X", false)).isNull();
        // Once it differs it is the user's input, and is checked like any other.
        assertThat(FloorMapFieldValidation.checkNumber("1.2x", "1.23", "Position X", false))
                .isEqualTo("Position X must be a number.");
    }
}
