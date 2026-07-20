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

import stroom.util.json.JsonUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A test-only {@link ValueAccessor} implementation backed by
 * {@code Map<String, Object>}. Uses {@link JsonUtil} (Jackson) for JSON
 * parsing/serialisation, which is available on the server classpath but NOT
 * in GWT.
 *
 * <p>This enables testing of {@link FloorMapEntryParser},
 * {@link FloorMapEditorModel}, and other shared logic without any GWT
 * dependency.</p>
 *
 * <p>Paths follow the same convention as the GWT accessors: a dot-prefixed
 * key such as {@code ".type"} maps to the JSON property {@code "type"}.</p>
 */
public class MapValueAccessor implements ValueAccessor {

    public static final MapValueAccessor INSTANCE = new MapValueAccessor();

    @Override
    public ParsedValue parse(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = JsonUtil.readValue(raw, Map.class);
            return new ParsedValue(map);
        } catch (final Exception e) {
            return null;
        }
    }

    @Override
    public ParsedValue createEmpty(final String rootName) {
        return new ParsedValue(new HashMap<String, Object>());
    }

    @Override
    public String getString(final ParsedValue value, final String path) {
        if (value == null || path == null) {
            return null;
        }
        final Map<String, Object> map = asMap(value);
        final Object val = map.get(toKey(path));
        return val != null ? val.toString() : null;
    }

    @Override
    public void setString(final ParsedValue value, final String path,
                          final String textValue) {
        if (value == null || path == null) {
            return;
        }
        final Map<String, Object> map = asMap(value);
        if (textValue != null) {
            map.put(toKey(path), textValue);
        } else {
            map.remove(toKey(path));
        }
    }

    @Override
    public double[] getArray(final ParsedValue value, final String path) {
        if (value == null || path == null) {
            return null;
        }
        final Map<String, Object> map = asMap(value);
        final Object val = map.get(toKey(path));
        if (val instanceof List<?>) {
            @SuppressWarnings("PatternVariableCanBeUsed") final List<?> list = (List<?>) val;
            final double[] result = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = ((Number) list.get(i)).doubleValue();
            }
            return result;
        }
        if (val instanceof double[]) {
            return (double[]) val;
        }
        return null;
    }

    @Override
    public void setArray(final ParsedValue value, final String path,
                         final double[] numbers) {
        if (value == null || path == null) {
            return;
        }
        final Map<String, Object> map = asMap(value);
        if (numbers != null) {
            // Store as a List<Double> for Jackson serialisation compatibility
            final java.util.ArrayList<Double> list = new java.util.ArrayList<>();
            for (final double d : numbers) {
                list.add(d);
            }
            map.put(toKey(path), list);
        } else {
            map.remove(toKey(path));
        }
    }

    @Override
    public Double getNumber(final ParsedValue value, final String path) {
        if (value == null || path == null) {
            return null;
        }
        final Map<String, Object> map = asMap(value);
        final Object val = map.get(toKey(path));
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        if (val instanceof String) {
            try {
                return Double.parseDouble(((String) val).trim());
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void setNumber(final ParsedValue value, final String path,
                          final Double number) {
        if (value == null || path == null) {
            return;
        }
        final Map<String, Object> map = asMap(value);
        if (number != null) {
            map.put(toKey(path), number);
        } else {
            map.remove(toKey(path));
        }
    }

    @Override
    public String serialize(final ParsedValue value) {
        if (value == null) {
            return "{}";
        }
        return JsonUtil.writeValueAsString(asMap(value));
    }

    @Override
    public boolean canParse(final String raw) {
        return raw != null && raw.trim().startsWith("{");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final ParsedValue value) {
        return (Map<String, Object>) value.getBacking();
    }

    private static String toKey(final String path) {
        if (path != null && path.startsWith(".")) {
            return path.substring(1);
        }
        return path;
    }
}
