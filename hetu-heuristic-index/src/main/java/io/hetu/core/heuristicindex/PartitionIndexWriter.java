/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
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
package io.hetu.core.heuristicindex;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import io.airlift.log.Logger;
import io.hetu.core.plugin.heuristicindex.index.btree.BTreeIndex;
import io.prestosql.spi.HetuConstant;
import io.prestosql.spi.connector.CreateIndexMetadata;
import io.prestosql.spi.filesystem.HetuFileSystemClient;
import io.prestosql.spi.heuristicindex.Index;
import io.prestosql.spi.heuristicindex.IndexWriter;
import io.prestosql.spi.heuristicindex.KeyValue;
import io.prestosql.spi.type.Type;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Indexes which needs to be created at table or partition level
 * needs to use this writer.
 */
public class PartitionIndexWriter
        implements IndexWriter
{
    private static final Logger LOG = Logger.get(FileIndexWriter.class);
    private static final String SYMBOL_TABLE_KEY_NAME = "__hetu__symboltable";
    private static final String PREFIX_KEY_NAME = "__hetu__partitionprefix";
    private static final String LAST_MODIFIED_KEY_NAME = "__hetu__lastmodified";
    private static final String MAX_MODIFIED_TIME = "__hetu__maxmodifiedtime";

    private Index partitionIndex;
    private String partition;
    private Random rand = new Random();
    protected AtomicInteger counter = new AtomicInteger(0); // symbol table counter
    private CreateIndexMetadata createIndexMetadata;
    private Lock persistLock = new ReentrantLock();
    protected Map<String, String> symbolTable;
    protected Map<Object, String> dataMap;
    protected Map<String, Long> lastModifiedTable;
    private Long maxLastModifiedTime = 0L;
    private Properties properties;
    private String pathPrefix;
    private HetuFileSystemClient fs;
    private Path root;

    public PartitionIndexWriter(CreateIndexMetadata createIndexMetadata, Properties connectorMetadata, HetuFileSystemClient fs, Path root)
    {
        this.createIndexMetadata = createIndexMetadata;
        this.fs = fs;
        this.root = root;
        properties = new Properties();
        symbolTable = new ConcurrentHashMap<>();
        dataMap = new ConcurrentHashMap<>();
        lastModifiedTable = new ConcurrentHashMap<>();
        if (createIndexMetadata.getPartitions() != null && !createIndexMetadata.getPartitions().isEmpty()) {
            partition = createIndexMetadata.getPartitions().get(0);
        }
    }

    @Override
    public void addData(Map<String, List<Object>> values, Properties connectorMetadata)
            throws IOException
    {
        String filePath = connectorMetadata.getProperty(HetuConstant.DATASOURCE_FILE_PATH);
        Path path = Paths.get(filePath);
        pathPrefix = path.getParent().toString();
        String fileName = path.getName(path.getNameCount() - 1).toString();

        if (Strings.isNullOrEmpty(partition)) {
            partition = path.getName(path.getNameCount() - 2).toString();
        }

        long lastModified = Long.parseLong((String) connectorMetadata.get(HetuConstant.DATASOURCE_FILE_MODIFICATION));
        String offset = String.valueOf(connectorMetadata.getProperty(HetuConstant.DATASOURCE_STRIPE_OFFSET));
        lastModifiedTable.put(fileName, lastModified);
        if (lastModified > maxLastModifiedTime) {
            maxLastModifiedTime = lastModified;
        }
        //TODO: Currently we only support index on single column. The order is not deterministic in current
        // IndexMetadata hence we cannot rely on it.
        fillDataMap(values, fileName, offset);
        LOG.debug("Symbol Table: " + symbolTable);
    }

    private void fillDataMap(Map<String, List<Object>> values, String fileName, String offset)
    {
        Map.Entry<String, List<Object>> valueEntry = values.entrySet().iterator().next();
        String code = null;
        if (this.symbolTable.containsKey(fileName)) {
            code = symbolTable.get(fileName);
        }
        else {
            code = String.valueOf(counter.incrementAndGet());
            this.symbolTable.put(fileName, code);
        }
        String splitData = code + ":" + offset;
        for (Object key : valueEntry.getValue()) {
            if (key != null) {
                String existing = dataMap.putIfAbsent(key, splitData);
                if (existing != null) {
                    String newData = getNewData(key, splitData);
                    boolean done = dataMap.replace(key, existing, newData);
                    while (!done) {
                        existing = dataMap.get(key);
                        newData = getNewData(key, splitData);
                        done = dataMap.replace(key, existing, newData);
                    }
                }
            }
        }
    }

    private String getNewData(Object key, String splitData)
    {
        String output = dataMap.get(key);
        StringBuilder sb = new StringBuilder(output);
        sb.append(",");
        sb.append(splitData);
        return sb.toString();
    }

    @Override
    public void persist()
            throws IOException
    {
        persistLock.lock();
        try {
            String serializedSymbolTable = serializeMap(symbolTable);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Symbol table size: " + symbolTable.size());
                LOG.debug("Output map size: " + dataMap.size());
                LOG.debug("symbol table: " + serializedSymbolTable);
                LOG.debug("path prefix: " + pathPrefix);
            }

            String dbPath = "";
            for (Map.Entry<String, Type> entry : createIndexMetadata.getIndexColumns()) {
                if (partition != null) {
                    dbPath = this.root + "/" + createIndexMetadata.getTableName() + "/" + entry.getKey() + "/" + partition;
                }
                else {
                    dbPath = this.root + "/" + createIndexMetadata.getTableName() + "/" + entry.getKey();
                }
                partitionIndex = HeuristicIndexFactory.createIndex(createIndexMetadata.getIndexType());
            }

            for (Map.Entry<Object, String> entry : dataMap.entrySet()) {
                KeyValue keyValue = new KeyValue(entry.getKey(), entry.getValue());
                partitionIndex.addKeyValues(Collections.singletonList(keyValue));
            }

            properties.put(SYMBOL_TABLE_KEY_NAME, serializedSymbolTable);
            properties.put(LAST_MODIFIED_KEY_NAME, serializeMap(lastModifiedTable));
            properties.put(MAX_MODIFIED_TIME, String.valueOf(maxLastModifiedTime));
            properties.put(PREFIX_KEY_NAME, pathPrefix);
            partitionIndex.setProperties(properties);
            Path filePath = Paths.get(dbPath + "/" + BTreeIndex.FILE_NAME);

            try {
                fs.createDirectories(filePath.getParent());
                partitionIndex.serialize(fs.newOutputStream(filePath));
            }
            catch (IOException e) {
                // roll back creation
                fs.delete(filePath);
                throw e;
            }
        }
        finally {
            partitionIndex.close();
            persistLock.unlock();
        }
    }

    private String serializeMap(Map<String, ?> input)
    {
        StringBuilder stringBuilder = new StringBuilder();
        input.forEach((key, value) -> {
            stringBuilder.append(key);
            stringBuilder.append(":");
            stringBuilder.append(value);
            stringBuilder.append(",");
        });
        return stringBuilder.toString();
    }

    @VisibleForTesting
    protected Map<Object, String> getDataMap()
    {
        return this.dataMap;
    }

    @VisibleForTesting
    protected Map<String, String> getSymbolTable()
    {
        return this.symbolTable;
    }
}
