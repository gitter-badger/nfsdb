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

import com.nfsdb.journal.exceptions.JournalException;
import com.nfsdb.journal.factory.configuration.JournalStructure;
import com.nfsdb.journal.lang.cst.JournalEntry;
import com.nfsdb.journal.test.tools.AbstractTest;
import com.nfsdb.journal.utils.Rnd;
import org.junit.Assert;
import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class GenericBinaryTest extends AbstractTest {

    private JournalWriter getGenericWriter() throws JournalException {
        return factory.writer(new JournalStructure("bintest") {{
                                  $bin("image");
                              }}
        );
    }

    private JournalWriter<BinContainer> getWriter() throws JournalException {
        return factory.writer(BinContainer.class, "bintest");
    }

    private List<byte[]> getBytes() {
        Rnd r = new Rnd();
        List<byte[]> bytes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            bytes.add(r.nextBytes((3 - i) * 1024));
        }
        return bytes;
    }

    private void writeInputStream(JournalWriter writer, List<byte[]> bytes) throws JournalException {
        JournalEntryWriter w;
        for (int i = 0; i < bytes.size(); i++) {
            w = writer.entryWriter();
            w.putBin(0, new ByteArrayInputStream(bytes.get(i)));
            w.append();
        }

        writer.commit();
    }

    private void writeOutputStream(JournalWriter writer, List<byte[]> expected) throws JournalException, IOException {
        for (int i = 0; i < expected.size(); i++) {
            JournalEntryWriter w = writer.entryWriter();
            try (OutputStream out = w.putBin(0)) {
                out.write(expected.get(i));
            }
            w.append();
        }
        writer.commit();

    }

    private List<byte[]> readOutputStream(Journal reader) {
        List<byte[]> result = new ArrayList<>();
        for (JournalEntry e : reader.rows()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            e.getBin(0, o);
            result.add(o.toByteArray());
        }
        return result;
    }

    private List<byte[]> readObject(Journal<BinContainer> reader) {
        List<byte[]> actual = new ArrayList<>();

        for (BinContainer c : reader) {
            actual.add(c.image.array());
        }

        return actual;
    }

    private void assertEquals(List<byte[]> expected, List<byte[]> actual) {
        for (int i = 0; i < expected.size(); i++) {
            Assert.assertArrayEquals(expected.get(i), actual.get(i));
        }
    }

    @Test
    public void testInputOutput() throws Exception {
        JournalWriter writer = getGenericWriter();
        List<byte[]> expected = getBytes();
        writeInputStream(writer, expected);
        assertEquals(expected, readOutputStream(writer));
    }

    @Test
    public void testInputObject() throws Exception {
        List<byte[]> bytes = getBytes();
        try (JournalWriter generic = getGenericWriter()) {
            writeInputStream(generic, bytes);
        }
        assertEquals(bytes, readObject(getWriter()));
    }

    @Test
    public void testOutputOutput() throws Exception {
        JournalWriter writer = getGenericWriter();
        List<byte[]> expected = getBytes();
        writeOutputStream(writer, expected);
        assertEquals(expected, readOutputStream(writer));
    }

    @Test
    public void testUnclosedOutputOutput() throws Exception {
        JournalWriter writer = getGenericWriter();
        List<byte[]> expected = getBytes();
        for (int i = 0; i < expected.size(); i++) {
            JournalEntryWriter w = writer.entryWriter();
            w.putBin(0).write(expected.get(i));
            w.append();
        }
        writer.commit();
        assertEquals(expected, readOutputStream(writer));
    }

    @Test
    public void testOutputInput() throws Exception {
        JournalWriter writer = getGenericWriter();
        List<byte[]> expected = getBytes();
        writeOutputStream(writer, expected);

        List<byte[]> actual = new ArrayList<>();
        for (JournalEntry e : writer.rows()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = e.getBin(0);

            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            actual.add(out.toByteArray());
        }

        assertEquals(expected, actual);
    }

    public static class BinContainer {
        private ByteBuffer image;
    }
}
