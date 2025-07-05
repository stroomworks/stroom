package stroom.receive.rules.shared;

import stroom.util.shared.HasCaseInsensitiveForm.CaseInsensitiveConverter;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * The type of check performed on received data.
 */
public enum ReceiptCheckMode {
    /**
     * The feed status (RECEIVE|DROP|REJECT) for the feed of the received data will be checked by calling
     * the downstream stroom/proxy.
     */
    FEED_STATUS(true),
    /**
     * The meta attributes from the headers will be checked against the receipt policy rules to determine
     * whether the data should be accepted for receipt, rejected or silently dropped. ALL downstream
     * stroom-proxy instances in the chain must also use this mode if this mode is set.
     */
    RECEIPT_POLICY(true),
    /**
     * No check is performed. All data is accepted for receipt (subject to other checks
     * like presence of stream type).
     */
    RECEIVE_ALL(true),
    /**
     * No check is performed. All data is dropped.
     */
    DROP_ALL(false),
    /**
     * No check is performed. All data is rejected.
     */
    REJECT_ALL(false),
    ;

    private static final CaseInsensitiveConverter<ReceiptCheckMode> CASE_INSENSITIVE_CONVERTER =
            CaseInsensitiveConverter.create(ReceiptCheckMode.class);

    private final boolean canReceiveData;

    ReceiptCheckMode(final boolean canReceiveData) {
        this.canReceiveData = canReceiveData;
    }

    public static ReceiptCheckMode getDefault() {
        // Declared here so it is visible to gwt, proxy and stroom
        return FEED_STATUS;
    }

    /**
     * @return True if this {@link ReceiveAction} allows data to be received, subject to constraints.
     */
    public boolean canReceiveData() {
        return canReceiveData;
    }

    /**
     * Allow deserialisation from the enum's name in any case.
     */
    @SuppressWarnings("unused") // JSON de-ser
    @JsonCreator
    public static ReceiptCheckMode fromCaseInsensitiveString(final String name) {
        return CASE_INSENSITIVE_CONVERTER.convert(name);
    }
}
