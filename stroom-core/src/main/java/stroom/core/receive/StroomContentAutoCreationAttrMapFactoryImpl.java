package stroom.core.receive;

import stroom.meta.api.AttributeMap;
import stroom.meta.api.StandardHeaderArguments;
import stroom.proxy.StroomStatusCode;
import stroom.receive.common.AttributeMapFilter;
import stroom.receive.common.ContentAutoCreationAttrMapFilterFactory;
import stroom.receive.common.ReceiveAllAttributeMapFilter;
import stroom.receive.common.StroomStreamException;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;
import stroom.util.shared.UserDesc;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

public class StroomContentAutoCreationAttrMapFactoryImpl
        implements ContentAutoCreationAttrMapFilterFactory {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(
            StroomContentAutoCreationAttrMapFactoryImpl.class);

    private final Provider<AutoContentCreationConfig> autoContentCreationConfigProvider;
    private final Provider<ContentAutoCreationService> contentAutoCreationServiceProvider;

    @Inject
    public StroomContentAutoCreationAttrMapFactoryImpl(
            final Provider<AutoContentCreationConfig> autoContentCreationConfigProvider,
            final Provider<ContentAutoCreationService> contentAutoCreationServiceProvider) {

        this.autoContentCreationConfigProvider = autoContentCreationConfigProvider;
        this.contentAutoCreationServiceProvider = contentAutoCreationServiceProvider;
    }

    @Override
    public AttributeMapFilter create() {
        if (autoContentCreationConfigProvider.get().isEnabled()) {
            return new StroomContentAutoCreationAttrMapFilter(contentAutoCreationServiceProvider.get());
        } else {
            return ReceiveAllAttributeMapFilter.getInstance();
        }
    }


    // --------------------------------------------------------------------------------


    private record StroomContentAutoCreationAttrMapFilter(ContentAutoCreationService contentAutoCreationService)
            implements AttributeMapFilter {

        public boolean filter(final AttributeMap attributeMap) {

            final String feedName = NullSafe.get(
                    attributeMap.get(StandardHeaderArguments.FEED),
                    String::trim);
            final UserDesc userDesc;
            // These two have been added by RequestAuthenticatorImpl
            final String uploadUserId = NullSafe.get(
                    attributeMap.get(StandardHeaderArguments.UPLOAD_USER_ID),
                    String::trim);
            if (NullSafe.isNonBlankString(uploadUserId)) {
                final String uploadUsername = NullSafe.get(
                        attributeMap.get(StandardHeaderArguments.UPLOAD_USERNAME),
                        String::trim);
                userDesc = UserDesc.builder(uploadUserId)
                        .displayName(uploadUsername)
                        .build();
            } else {
                userDesc = null;
            }
            LOGGER.debug("filter() - feedName: '{}', userDesc: {}, attributeMap: {}",
                    feedName, userDesc, attributeMap);

            contentAutoCreationService.tryCreateFeed(
                            feedName, userDesc, attributeMap)
                    .orElseThrow(() ->
                            new StroomStreamException(StroomStatusCode.FEED_IS_NOT_DEFINED,
                                    attributeMap,
                                    "Failed to auto-create feed '" + feedName + "'"));
            return true;
        }
    }
}
