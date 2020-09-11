/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stroom.dashboard.shared.Dashboard;
import stroom.db.migration.mysql.V6_0_0_21__Dictionary;
import stroom.entity.server.util.ConnectionUtil;
import stroom.entity.shared.BaseResultList;
import stroom.entity.shared.NamedEntity;
import stroom.feed.server.FeedService;
import stroom.feed.shared.Feed;
import stroom.feed.shared.Feed.FeedStatus;
import stroom.feed.shared.FindFeedCriteria;
import stroom.importexport.server.ImportExportSerializer;
import stroom.importexport.shared.ImportState.ImportMode;
import stroom.index.server.IndexService;
import stroom.index.shared.FindIndexCriteria;
import stroom.index.shared.Index;
import stroom.jobsystem.server.JobNodeService;
import stroom.jobsystem.server.JobService;
import stroom.node.server.VolumeService;
import stroom.node.shared.FindVolumeCriteria;
import stroom.node.shared.Node;
import stroom.node.shared.Volume;
import stroom.pipeline.server.PipelineService;
import stroom.pipeline.shared.FindPipelineEntityCriteria;
import stroom.pipeline.shared.PipelineEntity;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.ExpressionTerm;
import stroom.security.server.AuthenticationService;
import stroom.security.server.UserService;
import stroom.statistics.server.sql.datasource.FindStatisticsEntityCriteria;
import stroom.statistics.server.sql.datasource.StatisticStoreEntityService;
import stroom.statistics.server.stroomstats.entity.FindStroomStatsStoreEntityCriteria;
import stroom.statistics.server.stroomstats.entity.StroomStatsStoreEntityService;
import stroom.statistics.shared.StatisticStore;
import stroom.statistics.shared.StatisticStoreEntity;
import stroom.stats.shared.StroomStatsStoreEntity;
import stroom.streamstore.server.StreamAttributeKeyService;
import stroom.streamstore.server.StreamStore;
import stroom.streamstore.shared.FindStreamAttributeKeyCriteria;
import stroom.streamstore.shared.QueryData;
import stroom.streamstore.shared.StreamAttributeConstants;
import stroom.streamstore.shared.StreamAttributeKey;
import stroom.streamstore.shared.StreamDataSource;
import stroom.streamstore.shared.StreamType;
import stroom.streamtask.server.StreamProcessorFilterService;
import stroom.streamtask.server.StreamProcessorService;
import stroom.util.io.FileUtil;
import stroom.util.io.StreamUtil;
import stroom.util.logging.LambdaLogger;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Script to create some base data for testing.
 */
public final class SetupSampleDataBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(SetupSampleDataBean.class);

    private static final String ROOT_DIR_NAME = "samples";

    private static final String STATS_COUNT_FEED_LARGE_NAME = "COUNT_FEED_LARGE";
    private static final String STATS_COUNT_FEED_SMALL_NAME = "COUNT_FEED_SMALL";
    private static final String STATS_VALUE_FEED_LARGE_NAME = "VALUE_FEED_LARGE";
    private static final String STATS_VALUE_FEED_SMALL_NAME = "VALUE_FEED_SMALL";
    // 52,000 is just over 3 days at 5000ms intervals
    private static final int STATS_ITERATIONS_LARGE = 52_000;
    // 1,000 is just over 1hr at 5000ms intervals
    private static final int STATS_ITERATIONS_SMALL = 1_000;
    private static final String STATS_COUNT_API_FEED_NAME = "COUNT_V3";
    private static final String STATS_COUNT_API_DATA_FILE = "./stroom-integrationtest/src/integrationTest/resources/SetupSampleDataBean_COUNT_V3.xml";

    private static final int LOAD_CYCLES = 10;

    @Resource
    private AuthenticationService authenticationService;
    @Resource
    private FeedService feedService;
    @Resource
    private StreamStore streamStore;
    @Resource
    private StreamAttributeKeyService streamAttributeKeyService;
    @Resource
    private CommonTestControl commonTestControl;
    @Resource
    private ImportExportSerializer importExportSerializer;
    @Resource
    private StreamProcessorFilterService streamProcessorFilterService;
    @Resource
    private StreamProcessorService streamProcessorService;
    @Resource
    private PipelineService pipelineService;
    @Resource
    private VolumeService volumeService;
    @Resource
    private IndexService indexService;
    @Resource
    private JobService jobService;
    @Resource
    private JobNodeService jobNodeService;
    @Resource
    private ContentImportService contentImportService;
    @Resource
    private StatisticStoreEntityService statisticsDataSourceService;
    @Resource
    private StroomStatsStoreEntityService stroomStatsStoreEntityService;

    public SetupSampleDataBean() {
    }

    private void createStreamAttributes() {
        final BaseResultList<StreamAttributeKey> list = streamAttributeKeyService
                .find(new FindStreamAttributeKeyCriteria());
        final HashSet<String> existingItems = new HashSet<>();
        for (final StreamAttributeKey streamAttributeKey : list) {
            existingItems.add(streamAttributeKey.getName());
        }
        for (final String name : StreamAttributeConstants.SYSTEM_ATTRIBUTE_FIELD_TYPE_MAP.keySet()) {
            if (!existingItems.contains(name)) {
                try {
                    streamAttributeKeyService.save(new StreamAttributeKey(name,
                            StreamAttributeConstants.SYSTEM_ATTRIBUTE_FIELD_TYPE_MAP.get(name)));
                } catch (final Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public void run(final boolean shutdown) throws IOException {
        // Ensure admin user exists.
        LOGGER.info("Creating admin user");
        authenticationService.getUserRef(UserService.ADMIN_USER_NAME);

//        createRandomExplorerNode(null, "", 0, 2);

        // Sample data/config can exist in many projects so here we define all
        // the root directories that we want to
        // process
        final Path[] rootDirs = new Path[]{StroomCoreServerTestFileUtil.getTestResourcesDir().resolve(ROOT_DIR_NAME),
                Paths.get("./stroom-statistics-server/src/test/resources").resolve(ROOT_DIR_NAME)};

        // process each root dir in turn
        for (final Path dir : rootDirs) {
            loadDirectory(shutdown, dir);
        }

        //Additional content is loaded by the gradle build in task downloadStroomContent


        // Add volumes to all indexes.
        FindVolumeCriteria findVolumeCriteria = new FindVolumeCriteria();
        findVolumeCriteria.getVolumeTypeSet().add(Volume.VolumeType.PRIVATE);
        final BaseResultList<Volume> volumeList = volumeService.find(findVolumeCriteria);
        if (volumeList.isEmpty()) {
            throw new RuntimeException("No PRIVATE volumes found to add to the indexes");
        }
        final BaseResultList<Index> indexList = indexService.find(new FindIndexCriteria());
        logEntities(indexList, "indexes");
        final Set<Volume> volumeSet = new HashSet<>(volumeList);

        for (final Index index : indexList) {
            LOGGER.info("Adding {} volume(s) to index {}", volumeSet.size(), index.getName());
            index.setVolumes(volumeSet);
            indexService.save(index);
        }

        // Create index pipeline processor filters
        createIndexingProcessorFilter("Example index", StreamType.EVENTS, Optional.empty());
        createIndexingProcessorFilter(
                "LAX_CARGO_VOLUME-INDEX", StreamType.RECORDS, Optional.of("LAX_CARGO_VOLUME"));
        createIndexingProcessorFilter(
                "BROADBAND_SPEED_TESTS-INDEX", StreamType.RECORDS, Optional.of("BROADBAND_SPEED_TESTS"));

        final List<Feed> feeds = feedService.find(new FindFeedCriteria());
        logEntities(feeds, "feeds");

        generateSampleStatisticsData();

        // code to check that the statisticsDataSource objects are stored
        // correctly
        final BaseResultList<StatisticStoreEntity> statisticsDataSources = statisticsDataSourceService
                .find(FindStatisticsEntityCriteria.instance());
        logEntities(statisticsDataSources, "statisticStores");

        final BaseResultList<StroomStatsStoreEntity> stroomStatsStoreEntities = stroomStatsStoreEntityService
                .find(FindStroomStatsStoreEntityCriteria.instance());
        logEntities(stroomStatsStoreEntities, "stroomStatsStores");

        // Create stream processors for all feeds.
        for (final Feed feed : feeds) {
            // Find the pipeline for this feed.
            final BaseResultList<PipelineEntity> pipelines = pipelineService
                    .find(new FindPipelineEntityCriteria(feed.getName()));

            if (pipelines == null || pipelines.size() == 0) {
                LOGGER.warn("No pipeline found for feed '" + feed.getName() + "'");
            } else if (pipelines.size() > 1) {
                LOGGER.warn("More than 1 pipeline found for feed '" + feed.getName() + "'");
            } else {
                final PipelineEntity pipeline = pipelines.getFirst();

                // Create a processor for this feed.
                final QueryData criteria = new QueryData.Builder()
                        .dataSource(StreamDataSource.STREAM_STORE_DOC_REF)
                        .expression(new ExpressionOperator.Builder(ExpressionOperator.Op.AND)
                                .addTerm(StreamDataSource.FEED_NAME, ExpressionTerm.Condition.EQUALS, feed.getName())
                                .addOperator(new ExpressionOperator.Builder(ExpressionOperator.Op.OR)
                                        .addTerm(StreamDataSource.STREAM_TYPE_NAME, ExpressionTerm.Condition.EQUALS, StreamType.RAW_EVENTS.getName())
                                        .addTerm(StreamDataSource.STREAM_TYPE_NAME, ExpressionTerm.Condition.EQUALS, StreamType.RAW_REFERENCE.getName())
                                        .build())
                                .build())
                        .build();
                streamProcessorFilterService.createNewFilter(pipeline, criteria, true, 10);
            }
        }

        try (final Connection connection = ConnectionUtil.getConnection()) {
            new V6_0_0_21__Dictionary().migrate(connection);
        } catch (final Exception e) {
            LOGGER.error(e.getMessage());
        }

        if (shutdown) {
            commonTestControl.shutdown();
        }
    }

    private void createIndexingProcessorFilter(
            final String pipelineName,
            final StreamType sourceStreamType,
            final Optional<String> optFeedName) {

        final BaseResultList<PipelineEntity> pipelines = pipelineService
                .find(new FindPipelineEntityCriteria(pipelineName));

        if (pipelines == null || pipelines.size() != 1) {
            throw new RuntimeException(LambdaLogger.buildMessage(
                    "Expecting to find one pipeline with name [{}]", pipelineName));
        } else {
            final PipelineEntity pipeline = pipelines.getFirst();

            ExpressionOperator.Builder expressionBuilder = new ExpressionOperator.Builder(ExpressionOperator.Op.AND)
                    .addTerm(
                            StreamDataSource.STREAM_TYPE_NAME,
                            ExpressionTerm.Condition.EQUALS,
                            sourceStreamType.getName());

            optFeedName.ifPresent(feedName ->
                    expressionBuilder.addTerm(StreamDataSource.FEED_NAME, ExpressionTerm.Condition.EQUALS, feedName));

            // Create a processor for this feed.
            final QueryData criteria = new QueryData.Builder()
                    .dataSource(StreamDataSource.STREAM_STORE_DOC_REF)
                    .expression(expressionBuilder.build())
                    .build();

            LOGGER.info("Creating processor filter on {} with criteria\n{}",
                    pipeline.getName(), criteria.getExpression().toMultiLineString());

            streamProcessorFilterService.createNewFilter(pipeline, criteria, true, 10);
        }
    }

    private static void logEntities(BaseResultList<? extends NamedEntity> entities, String entityTypes) {
        logEntities(entities.getValues(), entityTypes);
    }

    private static void logEntities(List<? extends NamedEntity> entities, String entityTypes) {
        LOGGER.info("Listing loaded {}:", entityTypes);
        entities.stream()
                .map(NamedEntity::getName)
                .sorted()
                .forEach(name -> LOGGER.info("  {}", name));
    }

    public void loadDirectory(final boolean shutdown, final Path importRootDir) throws IOException {
        LOGGER.info("Loading sample data for directory: " + FileUtil.getCanonicalPath(importRootDir));

        final Path configDir = importRootDir.resolve("config");
        final Path dataDir = importRootDir.resolve("input");
        final Path exampleDataDir = importRootDir.resolve("example_data");

        createStreamAttributes();

        if (Files.exists(configDir)) {
            // Load config.
            importExportSerializer.read(configDir, null, ImportMode.IGNORE_CONFIRMATION);

            // Enable all flags for all feeds.
            final List<Feed> feeds = feedService.find(new FindFeedCriteria());
            for (final Feed feed : feeds) {
                feed.setStatus(FeedStatus.RECEIVE);
                feedService.save(feed);
            }

            LOGGER.info("Node count = " + commonTestControl.countEntity(Node.class));
            LOGGER.info("Volume count = " + commonTestControl.countEntity(Volume.class));
            LOGGER.info("Feed count = " + commonTestControl.countEntity(Feed.class));
            LOGGER.info("StreamAttributeKey count = " + commonTestControl.countEntity(StreamAttributeKey.class));
            LOGGER.info("Dashboard count = " + commonTestControl.countEntity(Dashboard.class));
            LOGGER.info("Pipeline count = " + commonTestControl.countEntity(PipelineEntity.class));
            LOGGER.info("Index count = " + commonTestControl.countEntity(Index.class));
            LOGGER.info("StatisticDataSource count = " + commonTestControl.countEntity(StatisticStore.class));

        } else {
            LOGGER.info("Directory {} doesn't exist so skipping", configDir);
        }

        if (Files.exists(dataDir)) {
            // Load data.
            final DataLoader dataLoader = new DataLoader(feedService, streamStore);

            // We spread the received time over 10 min intervals to help test
            // repo
            // layout start 2 weeks ago.
            final long dayMs = 1000 * 60 * 60 * 24;
            final long tenMinMs = 1000 * 60 * 10;
            long startTime = System.currentTimeMillis() - (14 * dayMs);

            // Load each data item 10 times to create a reasonable amount to
            // test.
            final Feed fd = dataLoader.getFeed("DATA_SPLITTER-EVENTS");
            for (int i = 0; i < LOAD_CYCLES; i++) {
                LOGGER.info("Loading data from {}, iteration {}", dataDir.toAbsolutePath().toString(), i);
                // Load reference data first.
                dataLoader.read(dataDir, true, startTime);
                startTime += tenMinMs;

                // Then load event data.
                dataLoader.read(dataDir, false, startTime);
                startTime += tenMinMs;

                // Load some randomly generated data.
                final String randomData = createRandomData();
                dataLoader.loadInputStream(fd, "Gen data", StreamUtil.stringToStream(randomData), false, startTime);
                startTime += tenMinMs;
            }
        } else {
            LOGGER.info("Directory {} doesn't exist so skipping", dataDir);
        }

        // Load the example data that we don't want to duplicate as is done above
        if (Files.exists(exampleDataDir)) {
            LOGGER.info("Loading example data from {}", exampleDataDir.toAbsolutePath().toString());
            // Load data.
            final DataLoader dataLoader = new DataLoader(feedService, streamStore);
            long startTime = System.currentTimeMillis();

            // Then load event data.
            dataLoader.read(exampleDataDir, false, startTime);
        } else {
            LOGGER.info("Directory {} doesn't exist so skipping", exampleDataDir);
        }

        // streamTaskCreator.doCreateTasks();

        // // Add an index.
        // final Index index = addIndex();
        // addUserSearch(index);
        // addDictionarySearch(index);
    }

    private void loadStatsData(final DataLoader dataLoader,
                               final String feedName,
                               final int iterations,
                               final Instant startTime,
                               final BiFunction<Integer, Instant, String> dataGenerationFunction) {
        try {
            LOGGER.info("Generating statistics test data for feed {}", feedName);

            final Feed feed = dataLoader.getFeed(feedName);

            dataLoader.loadInputStream(
                    feed,
                    "Auto generated statistics data",
                    StreamUtil.stringToStream(dataGenerationFunction.apply(iterations, startTime)),
                    false,
                    startTime.toEpochMilli());
        } catch (final RuntimeException e) {
            LOGGER.error("Feed {} does not exist so cannot load the sample statistics data", feedName, e);
        }
    }

    /**
     * Generates some sample statistics data in two feeds. If the feed doesn't
     * exist it will fail silently
     */
    private void generateSampleStatisticsData() {
        final DataLoader dataLoader = new DataLoader(feedService, streamStore);
        final long startTime = System.currentTimeMillis();

        //keep the big and small feeds apart in terms of their event times
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfAWeekAgo = startOfToday.minus(7, ChronoUnit.DAYS);

        loadStatsData(
                dataLoader,
                STATS_COUNT_FEED_LARGE_NAME,
                STATS_ITERATIONS_LARGE,
                startOfAWeekAgo,
                GenerateSampleStatisticsData::generateCountData);

        loadStatsData(
                dataLoader,
                STATS_COUNT_FEED_SMALL_NAME,
                STATS_ITERATIONS_SMALL,
                startOfToday,
                GenerateSampleStatisticsData::generateCountData);

        loadStatsData(
                dataLoader,
                STATS_VALUE_FEED_LARGE_NAME,
                STATS_ITERATIONS_LARGE,
                startOfAWeekAgo,
                GenerateSampleStatisticsData::generateValueData);

        loadStatsData(
                dataLoader,
                STATS_VALUE_FEED_SMALL_NAME,
                STATS_ITERATIONS_SMALL,
                startOfToday,
                GenerateSampleStatisticsData::generateValueData);

        try {
            final Feed apiFeed = dataLoader.getFeed(STATS_COUNT_API_FEED_NAME);
            String sampleData = new String(Files.readAllBytes(Paths.get(STATS_COUNT_API_DATA_FILE)));

            dataLoader.loadInputStream(
                    apiFeed,
                    "Sample statistics count data for export to API",
                    StreamUtil.stringToStream(sampleData),
                    false,
                    startTime);
        } catch (final RuntimeException | IOException e) {
            LOGGER.warn("Feed {} does not exist so cannot load the sample count for export to API statistics data.",
                    STATS_COUNT_API_FEED_NAME);
        }
    }

    private String createRandomData() {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy,HH:mm:ss");
        final ZonedDateTime refDateTime = ZonedDateTime.of(2010, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        final StringBuilder sb = new StringBuilder();
        sb.append("Date,Time,FileNo,LineNo,User,Message\n");

        for (int i = 0; i < 1000; i++) {
            final ZonedDateTime dateTime = refDateTime.plusSeconds((long) (Math.random() * 10000));
            sb.append(formatter.format(dateTime));
            sb.append(",");
            sb.append(createNum(4));
            sb.append(",");
            sb.append(createNum(10));
            sb.append(",user");
            sb.append(createNum(10));
            sb.append(",Some message ");
            sb.append(createNum(10));
            sb.append("\n");
        }
        return sb.toString();
    }

    private String createNum(final int max) {
        return String.valueOf((int) (Math.random() * max) + 1);
    }

    // private Folder get(String name) {
    // Folder parentGroup = null;
    // Folder folder = null;
    //
    // String[] parts = name.split("/");
    // for (String part : parts) {
    // parentGroup = folder;
    // folder = folderService.loadByName(parentGroup, part);
    // }
    // return folder;
    // }
    //
    // private Index addIndex() {
    // try {
    // final Folder folder = get("Indexes/Example index");
    // final Pipeline indexTranslation = findTranslation("Example index");
    // return setupIndex(folder, "Example index", indexTranslation);
    //
    // } catch (final IOException e) {
    // throw new RuntimeException(e.getMessage(), e);
    // }
    // }
    //
    // private Pipeline findTranslation(final String name) {
    // final FindPipelineCriteria findTranslationCriteria = new
    // FindPipelineCriteria();
    // findTranslationCriteria.setName(name);
    // final BaseResultList<Pipeline> list = pipelineService
    // .find(findTranslationCriteria);
    // if (list != null && list.size() > 0) {
    // return list.getFirst();
    // }
    //
    // throw new RuntimeException("No translation found with name: " + name);
    // }
    //
    // private XSLT findXSLT(final String name) {
    // final FindXSLTCriteria findXSLTCriteria = new FindXSLTCriteria();
    // findXSLTCriteria.setName(name);
    // final BaseResultList<XSLT> list = xsltService.find(findXSLTCriteria);
    // if (list != null && list.size() > 0) {
    // return list.getFirst();
    // }
    //
    // throw new RuntimeException("No translation found with name: " + name);
    // }
    //
    // private Index setupIndex(final Folder folder,
    // final String indexName, final Pipeline indexTranslation)
    // throws IOException {
    // Index index = new Index();
    // index.setFolder(folder);
    // index.setName(indexName);
    //
    // index = indexService.save(index);
    //
    // return index;
    // }
    //
    // private void addUserSearch(final Index index) {
    // final Folder folder = get(SEARCH + "/Search Examples");
    // final XSLT resultXSLT = findXSLT("Search Result Table - Show XML");
    //
    // final SearchExpressionTerm content1 = new SearchExpressionTerm();
    // content1.setField("UserId");
    // content1.setValue("userone");
    // final SearchExpressionOperator andOperator = new
    // SearchExpressionOperator(
    // true);
    // andOperator.addChild(content1);
    //
    // // FIXME : Set result pipeline.
    // final Search expression = new Search(index, null, andOperator);
    // expression.setName("User search");
    // expression.setFolder(folder);
    // searchExpressionService.save(expression);
    //
    // final DictionaryDocument dictionary = new Dictionary();
    // dictionary.setName("User list");
    // dictionary.setWords("userone\nuser1");
    // }
    //
    // private void addDictionarySearch(final Index index) {
    // final Folder folder = get(SEARCH + "/Search Examples");
    // final XSLT resultXSLT = findXSLT("Search Result Table - Show XML");
    //
    // final DictionaryDocument dictionary = new Dictionary();
    // dictionary.setName("User list");
    // dictionary.setWords("userone\nuser1");
    // dictionary.setFolder(folder);
    //
    // dictionaryStore.save(dictionary);
    //
    // final SearchExpressionTerm content1 = new SearchExpressionTerm();
    // content1.setField("UserId");
    // content1.setOperator(Operator.IN_DICTIONARY);
    // content1.setValue("User list");
    // final SearchExpressionOperator andOperator = new
    // SearchExpressionOperator(
    // true);
    // andOperator.addChild(content1);
    //
    // // FIXME : Set result pipeline.
    // final Search expression = new Search(index, null, andOperator);
    // expression.setName("Dictionary search");
    // expression.setFolder(folder);
    //
    // searchExpressionService.save(expression);
    // }
}