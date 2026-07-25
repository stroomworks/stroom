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

package stroom.query.language.functions;

@SuppressWarnings("unused") // Used by FunctionFactory
@FunctionDef(
        name = Sign.NAME,
        commonCategory = FunctionCategory.MATHEMATICS,
        commonReturnType = ValDouble.class,
        commonReturnDescription = "The sign of the supplied value: -1, 0 or 1.",
        signatures = @FunctionSignature(
                description = "The sign of the supplied value: -1, 0 or 1.",
                args = @FunctionArg(
                        name = "value",
                        description = "Numeric field, function or a constant.",
                        argType = ValNumber.class)))
class Sign extends NumericFunction {

    static final String NAME = "sign";
    private static final Calc CALC = new Calc();

    public Sign(final String name) {
        super(name, 1, 1);
    }

    @Override
    protected Calculator getCalculator() {
        return CALC;
    }

    static class Calc extends Calculator {

        private static final ValInteger ZERO = ValInteger.create(0);

        @Override
        Val calc(final Val current, final Val value) {
            // A unary transform: ignore the accumulator; super.calc handles null/error/non-number checks and then
            // calls op with the value. (A null/non-numeric child is short-circuited by NumericFunction before here.)
            return super.calc(ZERO, value);
        }

        @Override
        double op(final double cur, final double val) {
            return Math.signum(val);
        }
    }
}
