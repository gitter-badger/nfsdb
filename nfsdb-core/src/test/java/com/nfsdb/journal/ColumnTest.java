/*
 * Copyright (c) 2014. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.journal;

import com.nfsdb.journal.column.FixedColumn;
import com.nfsdb.journal.column.MappedFile;
import com.nfsdb.journal.column.MappedFileImpl;
import com.nfsdb.journal.column.VariableColumn;
import com.nfsdb.journal.exceptions.JournalException;
import com.nfsdb.journal.utils.ByteBuffers;
import com.nfsdb.journal.utils.Files;
import com.nfsdb.journal.utils.Rnd;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;

public class ColumnTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    private File dataFile;
    private File indexFile;

    @Before
    public void setUp() throws JournalException {
        dataFile = new File(temporaryFolder.getRoot(), "col.d");
        indexFile = new File(temporaryFolder.getRoot(), "col.i");
    }

    @After
    public void tearDown() throws Exception {
        Files.deleteOrException(dataFile);
        Files.deleteOrException(indexFile);
    }

    @Test
    public void testFixedWidthColumns() throws JournalException {


        MappedFile mf = new MappedFileImpl(dataFile, 22, JournalMode.APPEND);

        try (FixedColumn pcc = new FixedColumn(mf, 4)) {
            for (int i = 0; i < 10000; i++) {
                pcc.putInt(i);
                pcc.commit();
            }
        }

        MappedFile mf2 = new MappedFileImpl(dataFile, 22, JournalMode.READ);

        try (FixedColumn pcc2 = new FixedColumn(mf2, 4)) {
            Assert.assertEquals(66, pcc2.getInt(66));
            Assert.assertEquals(4597, pcc2.getInt(4597));
            Assert.assertEquals(120, pcc2.getInt(120));
            Assert.assertEquals(4599, pcc2.getInt(4599));
        }

        MappedFile mf3 = new MappedFileImpl(dataFile, 22, JournalMode.READ);
        try (FixedColumn pcc3 = new FixedColumn(mf3, 4)) {
            Assert.assertEquals(4598, pcc3.getInt(4598));
        }
    }

    @Test
    public void testVarcharColumn() throws JournalException {
        final int recordCount = 10000;

        MappedFile df1 = new MappedFileImpl(dataFile, 22, JournalMode.APPEND);
        MappedFile idxFile1 = new MappedFileImpl(indexFile, 22, JournalMode.APPEND);

        try (VariableColumn varchar1 = new VariableColumn(df1, idxFile1)) {
            for (int i = 0; i < recordCount; i++) {
                varchar1.putStr("s" + i);
                varchar1.commit();
            }
        }

        MappedFile df2 = new MappedFileImpl(dataFile, 22, JournalMode.APPEND);
        MappedFile idxFile2 = new MappedFileImpl(indexFile, 22, JournalMode.APPEND);

        try (VariableColumn varchar2 = new VariableColumn(df2, idxFile2)) {
            Assert.assertEquals(recordCount, varchar2.size());
            for (int i = 0; i < varchar2.size(); i++) {
                String s = varchar2.getStr(i);
                Assert.assertEquals("s" + i, s);
            }
        }
    }

    @Test
    public void testVarcharNulls() throws JournalException {
        MappedFile df1 = new MappedFileImpl(dataFile, 22, JournalMode.APPEND);
        MappedFile idxFile1 = new MappedFileImpl(indexFile, 22, JournalMode.APPEND);

        try (VariableColumn varchar1 = new VariableColumn(df1, idxFile1)) {
            varchar1.putStr("string1");
            varchar1.commit();
            varchar1.putStr("string2");
            varchar1.commit();
            varchar1.putNull();
            varchar1.commit();
            varchar1.putStr("string3");
            varchar1.commit();
            varchar1.putNull();
            varchar1.commit();
            varchar1.putStr("string4");
            varchar1.commit();
        }

        MappedFile df2 = new MappedFileImpl(dataFile, 22, JournalMode.READ);
        MappedFile idxFile2 = new MappedFileImpl(indexFile, 22, JournalMode.READ);

        try (VariableColumn varchar2 = new VariableColumn(df2, idxFile2)) {
            Assert.assertEquals("string1", varchar2.getStr(0));
            Assert.assertEquals("string2", varchar2.getStr(1));
//            Assert.assertNull(varchar2.getStr(2));
            Assert.assertEquals("string3", varchar2.getStr(3));
//            Assert.assertNull(varchar2.getStr(4));
            Assert.assertEquals("string4", varchar2.getStr(5));
        }
    }

    @Test
    public void testTruncate() throws JournalException {

        MappedFile df1 = new MappedFileImpl(dataFile, 22, JournalMode.APPEND);
        MappedFile idxFile1 = new MappedFileImpl(indexFile, 22, JournalMode.APPEND);

        try (VariableColumn varchar1 = new VariableColumn(df1, idxFile1)) {
            varchar1.putStr("string1");
            varchar1.commit();
            varchar1.putStr("string2");
            varchar1.commit();
            varchar1.putNull();
            varchar1.commit();
            varchar1.putStr("string3");
            varchar1.commit();
            varchar1.putNull();
            varchar1.commit();
            varchar1.putStr("string4");
            varchar1.commit();

            Assert.assertEquals(6, varchar1.size());
            varchar1.truncate(4);
            varchar1.commit();
            Assert.assertEquals(4, varchar1.size());
            Assert.assertEquals("string1", varchar1.getStr(0));
            Assert.assertEquals("string2", varchar1.getStr(1));
//            Assert.assertNull(varchar1.getStr(2));
            Assert.assertEquals("string3", varchar1.getStr(3));

        }

        MappedFile df2 = new MappedFileImpl(dataFile, 22, JournalMode.READ);
        MappedFile idxFile12 = new MappedFileImpl(indexFile, 22, JournalMode.READ);

        try (VariableColumn varchar2 = new VariableColumn(df2, idxFile12)) {
            Assert.assertEquals("string1", varchar2.getStr(0));
            Assert.assertEquals("string2", varchar2.getStr(1));
//            Assert.assertNull(varchar2.getStr(2));
            Assert.assertEquals("string3", varchar2.getStr(3));
        }
    }

    @Test
    public void testFixedWidthFloat() throws Exception {
        try (FixedColumn col = new FixedColumn(new MappedFileImpl(dataFile, 22, JournalMode.APPEND), 4)) {
            int max = 150;
            for (int i = 0; i < max; i++) {
                col.putFloat((max - i) + 0.33f);
                col.commit();
            }

            for (long l = 0; l < col.size(); l++) {
                Assert.assertEquals(max - l + 0.33f, col.getFloat(l), 0);
            }
        }
    }

    @Test
    public void testVarByteBuffer() throws Exception {
        // bit hint 12 = 4k buffer, length of stored buffer must be larger than 4k for proper test.
        MappedFile df1 = new MappedFileImpl(dataFile, 12, JournalMode.APPEND);
        MappedFile idxFile1 = new MappedFileImpl(indexFile, 12, JournalMode.APPEND);

        final Rnd random = new Rnd(System.currentTimeMillis(), System.currentTimeMillis());
        final int len = 5024;
        try (VariableColumn col = new VariableColumn(df1, idxFile1)) {
            ByteBuffer buf = ByteBuffer.allocate(len);
            String s = random.nextString(buf.remaining() / 2);
            ByteBuffers.putStr(buf, s);
            buf.flip();
            col.putBin(buf);
            col.commit();

            ByteBuffer bb = ByteBuffer.allocate(col.getBinSize(0));
            col.getBin(0, bb);
            bb.flip();
            char chars[] = new char[bb.remaining() / 2];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = bb.getChar();
            }
            String actual = new String(chars);
            Assert.assertEquals(s, actual);
        }
    }

    @Test
    public void testTwoByteEdges() throws JournalException {

        Rnd r = new Rnd();
        String s1 = r.nextString(65000);
        String s2 = r.nextString(65000);
        MappedFile df1 = new MappedFileImpl(dataFile, 22, JournalMode.APPEND);
        MappedFile idxFile1 = new MappedFileImpl(indexFile, 22, JournalMode.APPEND);

        try (VariableColumn varchar1 = new VariableColumn(df1, idxFile1)) {

            varchar1.putStr(s1);
            varchar1.commit();
            varchar1.putStr(s2);
            varchar1.commit();
        }

        MappedFile df2 = new MappedFileImpl(dataFile, 22, JournalMode.READ);
        MappedFile idxFile2 = new MappedFileImpl(indexFile, 22, JournalMode.READ);

        try (VariableColumn varchar2 = new VariableColumn(df2, idxFile2)) {
            Assert.assertEquals(s1, varchar2.getStr(0));
            Assert.assertEquals(s2, varchar2.getStr(1));
        }
    }
}
