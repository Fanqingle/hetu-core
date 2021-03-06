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
package io.hetu.core.plugin.heuristicindex.index.btree;

import io.prestosql.spi.heuristicindex.Index;
import io.prestosql.spi.heuristicindex.KeyValue;
import io.prestosql.sql.tree.BetweenPredicate;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.StringLiteral;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestBTreeIndex
{
    @Test
    public void testStringKey()
            throws IOException
    {
        BTreeIndex index = new BTreeIndex();
        String value = "001:3,002:3,003:3,004:3,005:3,006:3,007:3,008:3,009:3,002:3,010:3,002:3,011:3,012:3,101:3,102:3,103:3,104:3,105:3,106:3,107:3,108:3,109:3,102:3,110:3,102:3,111:3,112:3";
        List<KeyValue> keyValues = new ArrayList<>();
        keyValues.add(new KeyValue("key1", value));
        index.addKeyValues(keyValues);
        ComparisonExpression comparisonExpression = new ComparisonExpression(ComparisonExpression.Operator.EQUAL,
                new StringLiteral("column"), new StringLiteral("key1"));
        assertTrue(index.matches(comparisonExpression), "Key should exists");
        index.close();
    }

    @Test
    public void testLongKey()
            throws IOException
    {
        BTreeIndex index = new BTreeIndex();
        String value = "001:3,002:3,003:3,004:3,005:3,006:3,007:3,008:3,009:3,002:3,010:3,002:3,011:3,012:3,101:3,102:3,103:3,104:3,105:3,106:3,107:3,108:3,109:3,102:3,110:3,102:3,111:3,112:3";
        List<KeyValue> keyValues = new ArrayList<>();
        Long key = Long.valueOf(1211231231);
        keyValues.add(new KeyValue(key, value));
        index.addKeyValues(keyValues);
        ComparisonExpression comparisonExpression = new ComparisonExpression(ComparisonExpression.Operator.EQUAL,
                new StringLiteral("column"), new LongLiteral(key.toString()));
        assertTrue(index.matches(comparisonExpression), "Key should exists");
    }

    @Test
    public void testLookup()
            throws IOException
    {
        BTreeIndex index = new BTreeIndex();
        for (int i = 0; i < 100; i++) {
            List<KeyValue> keyValues = new ArrayList<>();
            Long key = Long.valueOf(100 + i);
            String value = "value" + i;
            keyValues.add(new KeyValue(key, value));
            index.addKeyValues(keyValues);
        }
        ComparisonExpression comparisonExpression = new ComparisonExpression(ComparisonExpression.Operator.EQUAL, new StringLiteral("column"), new LongLiteral("101"));
        Iterator result = index.lookUp(comparisonExpression);
        assertNotNull(result, "Result shouldn't be null");
        assertTrue(result.hasNext());
        assertEquals("value1", result.next().toString());
        index.close();
    }

    @Test
    public void testBetween()
            throws IOException
    {
        BTreeIndex index = new BTreeIndex();
        for (int i = 0; i < 100; i++) {
            List<KeyValue> keyValues = new ArrayList<>();
            Long key = Long.valueOf(100 + i);
            String value = "value" + i;
            keyValues.add(new KeyValue(key, value));
            index.addKeyValues(keyValues);
        }
        BetweenPredicate betweenPredicate = new BetweenPredicate(new StringLiteral("column"), new LongLiteral("101"), new LongLiteral("110"));
        Iterator result = index.lookUp(betweenPredicate);
        assertNotNull(result, "Result shouldn't be null");
        assertTrue(result.hasNext());
        assertEquals("value1", result.next().toString());
        assertEquals("value2", result.next().toString());
        assertEquals("value3", result.next().toString());
        assertEquals("value4", result.next().toString());
        assertEquals("value5", result.next().toString());
        assertEquals("value6", result.next().toString());
        assertEquals("value7", result.next().toString());
        index.close();
    }

    @Test
    public void testSerialize()
            throws IOException
    {
        BTreeIndex index = new BTreeIndex();
        String value = "001:3,002:3,003:3,004:3,005:3,006:3,007:3,008:3,009:3,002:3,010:3,002:3,011:3,012:3,101:3,102:3,103:3,104:3,105:3,106:3,107:3,108:3,109:3,102:3,110:3,102:3,111:3,112:3";
        for (int i = 0; i < 1000; i++) {
            List<KeyValue> keyValues = new ArrayList<>();
            Long key = Long.valueOf(100 + i);
            keyValues.add(new KeyValue(key, value));
            index.addKeyValues(keyValues);
        }
        File file = File.createTempFile("test-serialize-", UUID.randomUUID().toString());
        index.serialize(new FileOutputStream(file));
        file.delete();
        index.close();
    }

    @Test
    public void testDeserialize()
            throws IOException
    {
        BTreeIndex index = new BTreeIndex();
        String value = "001:3,002:3,003:3,004:3,005:3,006:3,007:3,008:3,009:3,002:3,010:3,002:3,011:3,012:3,101:3,102:3,103:3,104:3,105:3,106:3,107:3,108:3,109:3,102:3,110:3,102:3,111:3,112:3";
        for (int i = 0; i < 1000; i++) {
            List<KeyValue> keyValues = new ArrayList<>();
            Long key = Long.valueOf(100 + i);
            keyValues.add(new KeyValue(key, value));
            index.addKeyValues(keyValues);
        }
        File file = File.createTempFile("test-serialize-", UUID.randomUUID().toString());
        index.serialize(new FileOutputStream(file));

        Index readindex = new BTreeIndex();
        readindex.deserialize(new FileInputStream(file));
        ComparisonExpression comparisonExpression = new ComparisonExpression(ComparisonExpression.Operator.EQUAL, new StringLiteral("column"), new LongLiteral("101"));

        Iterator result = readindex.lookUp(comparisonExpression);
        assertNotNull(result, "Result shouldn't be null");
        assertTrue(result.hasNext());
        assertEquals(value, result.next().toString());
        index.close();
    }
}
