package stroom.planb.impl.db;

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.meta.shared.Meta;
import stroom.planb.impl.PlanBDocCache;
import stroom.planb.impl.PlanBNameValidator;
import stroom.planb.impl.data.FileDescriptor;
import stroom.planb.impl.data.FileHashUtil;
import stroom.planb.impl.data.FileTransferClient;
import stroom.planb.impl.db.rangestate.RangeState;
import stroom.planb.impl.db.rangestate.RangeStateDb;
import stroom.planb.impl.db.session.Session;
import stroom.planb.impl.db.session.SessionDb;
import stroom.planb.impl.db.state.State;
import stroom.planb.impl.db.state.StateDb;
import stroom.planb.impl.db.temporalrangestate.TemporalRangeState;
import stroom.planb.impl.db.temporalrangestate.TemporalRangeStateDb;
import stroom.planb.impl.db.temporalstate.TemporalState;
import stroom.planb.impl.db.temporalstate.TemporalStateDb;
import stroom.planb.shared.AbstractPlanBSettings;
import stroom.planb.shared.PlanBDoc;
import stroom.util.io.FileUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;
import stroom.util.zip.ZipUtil;

import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class ShardWriters {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShardWriters.class);

    private final PlanBDocCache planBDocCache;
    private final ByteBuffers byteBuffers;
    private final StatePaths statePaths;
    private final FileTransferClient fileTransferClient;

    @Inject
    ShardWriters(final PlanBDocCache planBDocCache,
                 final ByteBuffers byteBuffers,
                 final StatePaths statePaths,
                 final FileTransferClient fileTransferClient) {
        this.planBDocCache = planBDocCache;
        this.byteBuffers = byteBuffers;
        this.statePaths = statePaths;
        this.fileTransferClient = fileTransferClient;
    }

    public ShardWriter createWriter(final Meta meta) {
        final Path dir;
        try {
            dir = statePaths.getWriterDir()
                    .resolve(meta.getId() + "_" + UUID.randomUUID());
            Files.createDirectories(dir);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ShardWriter(planBDocCache, byteBuffers, fileTransferClient, dir, meta);
    }

    public static class ShardWriter implements AutoCloseable {

        private final PlanBDocCache planBDocCache;
        private final ByteBuffers byteBuffers;
        private final FileTransferClient fileTransferClient;
        private final Path dir;
        private final Meta meta;
        private final Map<PlanBDoc, WriterInstance> writers = new HashMap<>();
        private final Map<String, Optional<PlanBDoc>> stateDocMap = new HashMap<>();

        public ShardWriter(final PlanBDocCache planBDocCache,
                           final ByteBuffers byteBuffers,
                           final FileTransferClient fileTransferClient,
                           final Path dir,
                           final Meta meta) {
            this.planBDocCache = planBDocCache;
            this.byteBuffers = byteBuffers;
            this.fileTransferClient = fileTransferClient;
            this.dir = dir;
            this.meta = meta;
        }

        public Optional<PlanBDoc> getDoc(final String mapName, final Consumer<String> errorConsumer) {
            Optional<PlanBDoc> result = Optional.empty();

            try {
                if (NullSafe.isBlankString(mapName)) {
                    throw new RuntimeException("Null map name");
                }

                result = stateDocMap.computeIfAbsent(mapName, k -> {
                    PlanBDoc doc = null;

                    try {
                        if (!PlanBNameValidator.isValidName(k)) {
                            throw new RuntimeException("Bad map name: " + k);
                        } else {
                            doc = planBDocCache.get(k);
                            if (doc == null) {
                                throw new RuntimeException("Unable to find state doc for map name: " + k);
                            }
                        }
                    } catch (final RuntimeException e) {
                        LOGGER.debug(e::getMessage, e);
                        errorConsumer.accept(e.getMessage());
                    }

                    return Optional.ofNullable(doc);
                });
            } catch (final RuntimeException e) {
                LOGGER.debug(e::getMessage, e);
                errorConsumer.accept(e.getMessage());
            }

            return result;
        }

        private static class WriterInstance implements AutoCloseable {

            private final Db<?, ?> lmdb;
            private final LmdbWriter writer;
            private final boolean synchroniseMerge;

            public WriterInstance(final Db<?, ?> lmdb, final boolean synchroniseMerge) {
                this.lmdb = lmdb;
                this.writer = lmdb.createWriter();
                this.synchroniseMerge = synchroniseMerge;
            }

            public void addState(final State state) {
                final StateDb db = (StateDb) lmdb;
                db.insert(writer, state);
            }

            public void addTemporalState(final TemporalState temporalState) {
                final TemporalStateDb db = (TemporalStateDb) lmdb;
                db.insert(writer, temporalState);
            }

            public void addRangeState(final RangeState rangeState) {
                final RangeStateDb db = (RangeStateDb) lmdb;
                db.insert(writer, rangeState);
            }

            public void addTemporalRangeState(final TemporalRangeState temporalRangeState) {
                final TemporalRangeStateDb db = (TemporalRangeStateDb) lmdb;
                db.insert(writer, temporalRangeState);
            }

            public void addSession(final Session session) {
                final SessionDb db = (SessionDb) lmdb;
                db.insert(writer, session);
            }

            public boolean isSynchroniseMerge() {
                return synchroniseMerge;
            }

            @Override
            public void close() {
                writer.close();
                lmdb.close();
            }
        }

        public void addState(final PlanBDoc doc,
                             final State state) {
            getWriter(doc).addState(state);
        }

        public void addTemporalState(final PlanBDoc doc,
                                     final TemporalState temporalState) {
            getWriter(doc).addTemporalState(temporalState);
        }

        public void addRangeState(final PlanBDoc doc,
                                   final RangeState rangeState) {
            getWriter(doc).addRangeState(rangeState);
        }

        public void addTemporalRangeState(final PlanBDoc doc,
                                           final TemporalRangeState temporalRangeState) {
            getWriter(doc).addTemporalRangeState(temporalRangeState);
        }

        public void addSession(final PlanBDoc doc,
                               final Session session) {
            getWriter(doc).addSession(session);
        }

        private WriterInstance getWriter(final PlanBDoc doc) {
            return writers.computeIfAbsent(doc, k ->
                    new WriterInstance(PlanBDb.open(doc,
                            getLmdbEnvDir(k),
                            byteBuffers,
                            false),
                            NullSafe.getOrElse(
                                    doc,
                                    PlanBDoc::getSettings,
                                    AbstractPlanBSettings::getSynchroniseMerge,
                                    false)));
        }

        private Path getLmdbEnvDir(final PlanBDoc doc) {
            try {
                final Path path = dir.resolve(doc.getUuid());
                Files.createDirectory(path);
                return path;
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            if (!writers.isEmpty()) {
                Path zipFile = null;
                try {
                    writers.values().forEach(WriterInstance::close);

                    final boolean synchroniseMerge = writers
                            .values()
                            .stream()
                            .anyMatch(WriterInstance::isSynchroniseMerge);

                    // Zip all and delete dir.
                    zipFile = dir.getParent().resolve(dir.getFileName().toString() + ".zip");
                    ZipUtil.zip(zipFile, dir);
                    FileUtil.deleteDir(dir);

                    final String fileHash = FileHashUtil.hash(zipFile);

                    final FileDescriptor fileDescriptor = new FileDescriptor(
                            System.currentTimeMillis(),
                            meta.getId(),
                            fileHash);
                    fileTransferClient.storePart(fileDescriptor, zipFile, synchroniseMerge);

                } catch (final IOException e) {
                    throw new UncheckedIOException(e);

                } finally {
                    try {
                        FileUtil.deleteDir(dir);
                        if (zipFile != null) {
                            Files.deleteIfExists(zipFile);
                        }
                    } catch (final Exception e) {
                        LOGGER.error(e.getMessage(), e);
                    }
                }
            }
        }
    }
}
