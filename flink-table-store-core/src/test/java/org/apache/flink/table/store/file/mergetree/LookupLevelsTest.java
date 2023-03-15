/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.file.mergetree;

import org.apache.flink.table.store.data.BinaryRow;
import org.apache.flink.table.store.data.GenericRow;
import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.data.serializer.InternalRowSerializer;
import org.apache.flink.table.store.file.KeyValue;
import org.apache.flink.table.store.file.format.FlushingFileFormat;
import org.apache.flink.table.store.file.io.DataFileMeta;
import org.apache.flink.table.store.file.io.KeyValueFileReaderFactory;
import org.apache.flink.table.store.file.io.KeyValueFileWriterFactory;
import org.apache.flink.table.store.file.io.RollingFileWriter;
import org.apache.flink.table.store.file.schema.KeyValueFieldsExtractor;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.file.utils.FileStorePathFactory;
import org.apache.flink.table.store.fs.FileIOFinder;
import org.apache.flink.table.store.fs.Path;
import org.apache.flink.table.store.io.cache.CacheManager;
import org.apache.flink.table.store.lookup.hash.HashLookupStoreFactory;
import org.apache.flink.table.store.options.MemorySize;
import org.apache.flink.table.store.table.SchemaEvolutionTableTestBase;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.RowKind;
import org.apache.flink.table.store.types.RowType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.apache.flink.table.store.CoreOptions.TARGET_FILE_SIZE;
import static org.apache.flink.table.store.file.io.DataFileTestUtils.row;
import static org.assertj.core.api.Assertions.assertThat;

/** Test {@link LookupLevels}. */
public class LookupLevelsTest {

    private static final String LOOKUP_FILE_PREFIX = "lookup-";

    @TempDir java.nio.file.Path tempDir;

    private final Comparator<InternalRow> comparator = Comparator.comparingInt(o -> o.getInt(0));

    private final RowType keyType = DataTypes.ROW(DataTypes.FIELD(0, "_key", DataTypes.INT()));
    private final RowType rowType =
            DataTypes.ROW(
                    DataTypes.FIELD(0, "key", DataTypes.INT()),
                    DataTypes.FIELD(1, "value", DataTypes.INT()));

    @Test
    public void testMultiLevels() throws IOException {
        Levels levels =
                new Levels(
                        comparator,
                        Arrays.asList(
                                newFile(1, kv(1, 11), kv(3, 33), kv(5, 5)),
                                newFile(2, kv(2, 22), kv(5, 55))),
                        3);
        LookupLevels lookupLevels = createLookupLevels(levels, MemorySize.ofMebiBytes(10));

        // only in level 1
        KeyValue kv = lookupLevels.lookup(row(1), 1);
        assertThat(kv).isNotNull();
        assertThat(kv.level()).isEqualTo(1);
        assertThat(kv.value().getInt(1)).isEqualTo(11);

        // only in level 2
        kv = lookupLevels.lookup(row(2), 1);
        assertThat(kv).isNotNull();
        assertThat(kv.level()).isEqualTo(2);
        assertThat(kv.value().getInt(1)).isEqualTo(22);

        // both in level 1 and level 2
        kv = lookupLevels.lookup(row(5), 1);
        assertThat(kv).isNotNull();
        assertThat(kv.level()).isEqualTo(1);
        assertThat(kv.value().getInt(1)).isEqualTo(5);

        // no exists
        kv = lookupLevels.lookup(row(4), 1);
        assertThat(kv).isNull();

        lookupLevels.close();
        assertThat(lookupLevels.lookupFiles().size()).isEqualTo(0);
    }

    @Test
    public void testMultiFiles() throws IOException {
        Levels levels =
                new Levels(
                        comparator,
                        Arrays.asList(
                                newFile(1, kv(1, 11), kv(2, 22)),
                                newFile(1, kv(4, 44), kv(5, 55)),
                                newFile(1, kv(7, 77), kv(8, 88)),
                                newFile(1, kv(10, 1010), kv(11, 1111))),
                        1);
        LookupLevels lookupLevels = createLookupLevels(levels, MemorySize.ofMebiBytes(10));

        Map<Integer, Integer> contains =
                new HashMap<Integer, Integer>() {
                    {
                        this.put(1, 11);
                        this.put(2, 22);
                        this.put(4, 44);
                        this.put(5, 55);
                        this.put(7, 77);
                        this.put(8, 88);
                        this.put(10, 1010);
                        this.put(11, 1111);
                    }
                };
        for (Map.Entry<Integer, Integer> entry : contains.entrySet()) {
            KeyValue kv = lookupLevels.lookup(row(entry.getKey()), 1);
            assertThat(kv).isNotNull();
            assertThat(kv.level()).isEqualTo(1);
            assertThat(kv.value().getInt(1)).isEqualTo(entry.getValue());
        }

        int[] notContains = new int[] {0, 3, 6, 9, 12};
        for (int key : notContains) {
            KeyValue kv = lookupLevels.lookup(row(key), 1);
            assertThat(kv).isNull();
        }

        lookupLevels.close();
        assertThat(lookupLevels.lookupFiles().size()).isEqualTo(0);
    }

    @Test
    public void testMaxDiskSize() throws IOException {
        List<DataFileMeta> files = new ArrayList<>();
        int fileNum = 10;
        int recordInFile = 100;
        for (int i = 0; i < fileNum; i++) {
            List<KeyValue> kvs = new ArrayList<>();
            for (int j = 0; j < recordInFile; j++) {
                int key = i * recordInFile + j;
                kvs.add(kv(key, key));
            }
            files.add(newFile(1, kvs.toArray(new KeyValue[0])));
        }
        Levels levels = new Levels(comparator, files, 1);
        LookupLevels lookupLevels = createLookupLevels(levels, MemorySize.ofKibiBytes(20));

        for (int i = 0; i < fileNum * recordInFile; i++) {
            KeyValue kv = lookupLevels.lookup(row(i), 1);
            assertThat(kv).isNotNull();
            assertThat(kv.level()).isEqualTo(1);
            assertThat(kv.value().getInt(1)).isEqualTo(i);
        }

        // some files are invalided
        long fileNumber = lookupLevels.lookupFiles().size();
        String[] lookupFiles =
                tempDir.toFile().list((dir, name) -> name.startsWith(LOOKUP_FILE_PREFIX));
        assertThat(lookupFiles).isNotNull();
        assertThat(fileNumber).isNotEqualTo(fileNum).isEqualTo(lookupFiles.length);

        lookupLevels.close();
        assertThat(lookupLevels.lookupFiles().size()).isEqualTo(0);
    }

    private LookupLevels createLookupLevels(Levels levels, MemorySize maxDiskSize) {
        return new LookupLevels(
                levels,
                comparator,
                new InternalRowSerializer(keyType),
                new InternalRowSerializer(rowType),
                file -> createReaderFactory().createRecordReader(0, file.fileName(), file.level()),
                () -> new File(tempDir.toFile(), LOOKUP_FILE_PREFIX + UUID.randomUUID()),
                new HashLookupStoreFactory(new CacheManager(2048, MemorySize.ofMebiBytes(1)), 0.75),
                Duration.ofHours(1),
                maxDiskSize);
    }

    private KeyValue kv(int key, int value) {
        return new KeyValue()
                .replace(GenericRow.of(key), RowKind.INSERT, GenericRow.of(key, value));
    }

    private DataFileMeta newFile(int level, KeyValue... records) throws IOException {
        RollingFileWriter<KeyValue, DataFileMeta> writer =
                createWriterFactory().createRollingMergeTreeFileWriter(level);
        for (KeyValue kv : records) {
            writer.write(kv);
        }
        writer.close();
        return writer.result().get(0);
    }

    private KeyValueFileWriterFactory createWriterFactory() {
        Path path = new Path(tempDir.toUri().toString());
        return KeyValueFileWriterFactory.builder(
                        FileIOFinder.find(path),
                        0,
                        keyType,
                        rowType,
                        new FlushingFileFormat("avro"),
                        new FileStorePathFactory(path),
                        TARGET_FILE_SIZE.defaultValue().getBytes())
                .build(BinaryRow.EMPTY_ROW, 0, null);
    }

    private KeyValueFileReaderFactory createReaderFactory() {
        Path path = new Path(tempDir.toUri().toString());
        KeyValueFileReaderFactory.Builder builder =
                KeyValueFileReaderFactory.builder(
                        FileIOFinder.find(path),
                        createSchemaManager(path),
                        0,
                        keyType,
                        rowType,
                        ignore -> new FlushingFileFormat("avro"),
                        new FileStorePathFactory(path),
                        new KeyValueFieldsExtractor() {
                            @Override
                            public List<DataField> keyFields(TableSchema schema) {
                                return keyType.getFields();
                            }

                            @Override
                            public List<DataField> valueFields(TableSchema schema) {
                                return schema.fields();
                            }
                        });
        return builder.build(BinaryRow.EMPTY_ROW, 0);
    }

    private SchemaManager createSchemaManager(Path path) {
        TableSchema tableSchema =
                new TableSchema(
                        0,
                        rowType.getFields(),
                        rowType.getFieldCount(),
                        Collections.emptyList(),
                        Collections.singletonList("key"),
                        Collections.emptyMap(),
                        "");
        Map<Long, TableSchema> schemas = new HashMap<>();
        schemas.put(tableSchema.id(), tableSchema);
        return new SchemaEvolutionTableTestBase.TestingSchemaManager(path, schemas);
    }
}