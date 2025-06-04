package stroom.planb.shared;

import stroom.docref.HasDisplayValue;

import java.util.List;

public enum StateValueType implements HasDisplayValue {
    // Treat all values as booleans.
    BOOLEAN("Boolean"), // 1 byte true/false
    // Treat all values as bytes.
    BYTE("Byte"), // 1 byte from +127 to -128
    // Treat all values as shorts.
    SHORT("Short"), // 2 bytes from +32,767 to -32,768
    // Treat all values as integers.
    INT("Integer"), // 4 bytes from +2,147,483,647 to -2,147,483,648
    // Treat all values as longs.
    LONG("Long"), // 8 bytes from +9,223,372,036,854,775,807 to -9,223,372,036,854,775,808
    // Treat all values as floats.
    FLOAT("Float"), // 4 bytes from 3.402,823,5 E+38 to 1.4 E-45
    // Treat all values as doubles.
    DOUBLE("Double"), // 8 bytes from 1.797,693,134,862,315,7 E+308 to 4.9 E-324
    // Treat all values as strings.
    STRING("String"),
    // Always use a UID lookup table to store all keys.
    UID_LOOKUP("UID lookup table"), // max 511 bytes, deduplicated data
    // Always use a lookup table to store all values. The value is a hash plus a sequence number.
    // Lookups deduplicate data and reduce storage requirements but impact performance.
    HASH_LOOKUP("Hash lookup table"), // unlimited bytes, deduplicated data
    // Use string, UID lookup or hash lookup depending on the length of the string.
    VARIABLE("Variable");

    public static final List<StateValueType> ORDERED_LIST = List.of(
            BOOLEAN,
            BYTE,
            SHORT,
            INT,
            LONG,
            FLOAT,
            DOUBLE,
            STRING,
            UID_LOOKUP,
            HASH_LOOKUP,
            VARIABLE);

    private final String displayValue;

    StateValueType(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
