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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * A 2D affine transformation matrix used to position and transform floor map
 * images and coordinates.
 * <p>
 * The six components {@code (a, b, c, d, e, f)} correspond to the SVG/CSS
 * {@code matrix(a, b, c, d, e, f)} notation, representing the transformation:
 * <pre>
 *   | a  c  e |
 *   | b  d  f |
 *   | 0  0  1 |
 * </pre>
 */
public class FloorMapTransformationMatrix {

    @JsonProperty
    private final double a;
    @JsonProperty
    private final double b;
    @JsonProperty
    private final double c;
    @JsonProperty
    private final double d;
    @JsonProperty
    private final double e;
    @JsonProperty
    private final double f;

    @JsonCreator
    public FloorMapTransformationMatrix(@JsonProperty("a") final double a,
                                        @JsonProperty("b") final double b,
                                        @JsonProperty("c") final double c,
                                        @JsonProperty("d") final double d,
                                        @JsonProperty("e") final double e,
                                        @JsonProperty("f") final double f) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.f = f;
    }

    public double getA() {
        return a;
    }

    public double getB() {
        return b;
    }

    public double getC() {
        return c;
    }

    public double getD() {
        return d;
    }

    public double getE() {
        return e;
    }

    public double getF() {
        return f;
    }

    /**
     * Formats this matrix as an SVG {@code matrix(a,b,c,d,e,f)} string.
     *
     * @return the SVG matrix attribute value
     */
    public String toSvgMatrix() {
        return "matrix(" + a + "," + b + "," + c + "," + d + "," + e + "," + f + ")";
    }

    /**
     * Returns the identity transformation (no scaling, rotation, or translation).
     *
     * @return the identity matrix
     */
    public static FloorMapTransformationMatrix identity() {
        return new FloorMapTransformationMatrix(1, 0, 0, 1, 0, 0);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FloorMapTransformationMatrix that = (FloorMapTransformationMatrix) o;
        return Double.compare(that.a, a) == 0 &&
                Double.compare(that.b, b) == 0 &&
                Double.compare(that.c, c) == 0 &&
                Double.compare(that.d, d) == 0 &&
                Double.compare(that.e, e) == 0 &&
                Double.compare(that.f, f) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(a, b, c, d, e, f);
    }

    @Override
    public String toString() {
        return toSvgMatrix();
    }

    /**
     * Creates a rotation-only transformation matrix for the given angle.
     *
     * @param degrees the rotation angle in degrees (counter-clockwise positive)
     * @return a new rotation matrix
     */
    public static FloorMapTransformationMatrix rotate(final double degrees) {
        final double radians = Math.toRadians(degrees);
        final double cos = Math.cos(radians);
        final double sin = Math.sin(radians);

        // a=cos, b=sin, c=-sin, d=cos, e=0, f=0
        return new FloorMapTransformationMatrix(cos, sin, -sin, cos, 0, 0);
    }

    /**
     * Computes the inverse of this transformation matrix.
     * <p>
     * If the matrix is singular (determinant ≈ 0), the identity matrix is
     * returned as a safe fallback.
     *
     * @return the inverse matrix, or identity if this matrix is non-invertible
     */
    public FloorMapTransformationMatrix inverse() {
        final double det = a * d - b * c;
        if (Math.abs(det) < 1e-9) {
            return identity();
        }
        final double invDet = 1.0 / det;
        final double invA = d * invDet;
        final double invB = -b * invDet;
        final double invC = -c * invDet;
        final double invD = a * invDet;
        final double invE = (c * f - d * e) * invDet;
        final double invF = (b * e - a * f) * invDet;
        return new FloorMapTransformationMatrix(invA, invB, invC, invD, invE, invF);
    }
}
