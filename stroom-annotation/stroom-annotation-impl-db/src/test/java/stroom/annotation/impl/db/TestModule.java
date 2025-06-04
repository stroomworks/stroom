package stroom.annotation.impl.db;

import stroom.cache.impl.CacheModule;
import stroom.collection.mock.MockCollectionModule;
import stroom.dictionary.mock.MockWordListProviderModule;
import stroom.docrefinfo.mock.MockDocRefInfoModule;
import stroom.meta.api.StreamFeedProvider;
import stroom.security.mock.MockSecurityContextModule;
import stroom.security.user.api.UserRefLookup;
import stroom.task.mock.MockTaskModule;
import stroom.test.common.MockMetrics;
import stroom.test.common.util.db.DbTestModule;
import stroom.util.db.ForceLegacyMigration;
import stroom.util.metrics.Metrics;
import stroom.util.shared.UserRef;

import com.google.inject.AbstractModule;

import java.util.Optional;

public class TestModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();
        install(new AnnotationDaoModule());
        install(new AnnotationDbModule());
        install(new MockCollectionModule());
        install(new MockDocRefInfoModule());
        install(new MockSecurityContextModule());
        install(new MockWordListProviderModule());
        install(new DbTestModule());
        install(new MockTaskModule());
        install(new CacheModule());

        // Not using all the DB modules so just bind to an empty anonymous class
        bind(ForceLegacyMigration.class).toInstance(new ForceLegacyMigration() {
        });
        bind(UserRefLookup.class).toInstance(new UserRefLookup() {
            @Override
            public Optional<UserRef> getByUuid(final String userUuid) {
                return Optional.of(UserRef.forUserUuid(userUuid));
            }

            @Override
            public UserRef decorate(final UserRef userRef) {
                return userRef;
            }
        });
        bind(StreamFeedProvider.class).toInstance(new StreamFeedProvider() {
            @Override
            public String getFeedName(final long id) {
                return "TEST_FEED_NAME";
            }
        });
        bind(Metrics.class).toInstance(new MockMetrics());
    }
//
//
//    @Provides
//    CollectionService collectionService() {
//        return new CollectionService() {
//            @Override
//            public Set<DocRef> getChildren(final DocRef folder, final String type) {
//                return null;
//            }
//
//            @Override
//            public Set<DocRef> getDescendants(final DocRef folder, final String type) {
//                return null;
//            }
//        };
//    }
//
//    @Provides
//    WordListProvider wordListProvider() {
//        return new WordListProvider() {
//
//            @Override
//            public List<DocRef> findByName(final String name) {
//                return List.of();
//            }
//
//            @Override
//            public Optional<DocRef> findByUuid(final String uuid) {
//                return Optional.empty();
//            }
//
//            @Override
//            public String getCombinedData(final DocRef dictionaryRef) {
//                return null;
//            }
//
//            @Override
//            public String[] getWords(final DocRef dictionaryRef) {
//                return null;
//            }
//
//            @Override
//            public WordList getCombinedWordList(final DocRef dictionaryRef,
//                                                final DocRefDecorator docRefDecorator) {
//                return null;
//            }
//        };
//    }
}
