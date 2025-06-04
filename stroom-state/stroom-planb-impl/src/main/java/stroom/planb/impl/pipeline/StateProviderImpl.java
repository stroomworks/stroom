package stroom.planb.impl.pipeline;

import stroom.planb.impl.PlanBDocCache;
import stroom.planb.impl.data.GetRequest;
import stroom.planb.impl.data.PlanBQueryService;
import stroom.planb.shared.PlanBDoc;
import stroom.query.language.functions.StateProvider;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Singleton
public class StateProviderImpl implements StateProvider {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StateProviderImpl.class);

    private final PlanBDocCache stateDocCache;
    private final Cache<GetRequest, Val> cache;
    private final PlanBQueryService planBQueryService;
    private final SecurityContext securityContext;

    @Inject
    public StateProviderImpl(final PlanBDocCache stateDocCache,
                             final PlanBQueryService planBQueryService,
                             final SecurityContext securityContext) {
        this.stateDocCache = stateDocCache;
        this.planBQueryService = planBQueryService;
        this.securityContext = securityContext;
        cache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(10, TimeUnit.MINUTES).build();
    }

    @Override
    public Val getState(final String mapName, final String keyName, final long effectiveTimeMs) {
        try {
            final String docName = mapName.toLowerCase(Locale.ROOT);
            final Optional<PlanBDoc> stateOptional = securityContext.useAsReadResult(() ->
                    Optional.ofNullable(stateDocCache.get(docName)));
            return stateOptional
                    .map(stateDoc -> {
                        final GetRequest request = new GetRequest(docName, keyName, effectiveTimeMs);
                        return cache.get(request, planBQueryService::getVal);
                    })
                    .orElse(ValNull.INSTANCE);
        } catch (final Exception e) {
            LOGGER.debug(e::getMessage, e);
            return null;
        }
    }
}
