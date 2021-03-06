/*
 * Copyright (c) 2014-2015. Vlad Ilyushchenko
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

package com.nfsdb.journal.column;

import com.nfsdb.journal.JournalMode;
import com.nfsdb.journal.exceptions.JournalException;
import com.nfsdb.journal.exceptions.JournalNoSuchFileException;
import com.nfsdb.journal.exceptions.JournalRuntimeException;
import com.nfsdb.journal.logging.Logger;
import com.nfsdb.journal.utils.ByteBuffers;
import com.nfsdb.journal.utils.Files;
import com.nfsdb.journal.utils.Lists;
import com.nfsdb.journal.utils.Unsafe;
import sun.nio.ch.DirectBuffer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MappedFileImpl implements MappedFile {

    private static final Logger LOGGER = Logger.getLogger(MappedFileImpl.class);
    private final File file;
    private final JournalMode mode;
    private final int bitHint;
    // reserve first 8 bytes in the file for storing pointer to logical end of file
    // so the actual data begins from "dataOffset"
    private final int dataOffset = 8;
    private FileChannel channel;
    private MappedByteBuffer offsetBuffer;
    private List<MappedByteBuffer> buffers;
    private List<ByteBufferWrapper> stitches;
    private MappedByteBuffer cachedBuffer;
    private long cachedBufferLo = -1;
    private long cachedBufferHi = -1;
    private long cachedAppendOffset = -1;
    private long cachedAddress;
    private long offsetDirectAddr;

    public MappedFileImpl(File file, int bitHint, JournalMode mode) throws JournalException {
        this.file = file;
        this.mode = mode;
        if (bitHint < 2) {
            LOGGER.warn("BitHint is too small for %s", file);
        }
        this.bitHint = bitHint;
        open();
        this.buffers = new ArrayList<>((int) (size() >>> bitHint) + 1);
        this.stitches = new ArrayList<>(buffers.size());
    }

    @Override
    public MappedByteBuffer getBuffer(long offset, int size) {
        if (offset >= cachedBufferLo && offset + size <= cachedBufferHi) {
            cachedBuffer.position((int) (offset - cachedBufferLo));
        } else {
            cachedBuffer = getBufferInternal(offset, size);
            cachedBufferLo = offset - cachedBuffer.position();
            cachedBufferHi = cachedBufferLo + cachedBuffer.limit();
            cachedAddress = ((DirectBuffer) cachedBuffer).address();
        }
        return cachedBuffer;
    }

    public long getAddress(long offset, int size) {
        if (offset >= cachedBufferLo && offset + size <= cachedBufferHi) {
            return cachedAddress + offset - cachedBufferLo;
        } else {
            return allocateAddress(offset, size);
        }
    }

    @Override
    public int getAddressSize(long offset) {
        if (offset >= cachedBufferLo && offset <= cachedBufferHi) {
            return (int) (cachedBufferHi - offset);
        } else {
            return 0;
        }
    }

    public void delete() {
        close();
        Files.delete(file);
    }

    @Override
    public void close() {
        try {
            unmap();
            channel.close();
        } catch (IOException e) {
            throw new JournalRuntimeException("Cannot close file", e);
        }
        offsetBuffer = ByteBuffers.release(offsetBuffer);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[file=" + file + ", appendOffset=" + getAppendOffset() + "]";
    }

    public long getAppendOffset() {
        if (cachedAppendOffset != -1 && (mode == JournalMode.APPEND || mode == JournalMode.BULK_APPEND)) {
            return cachedAppendOffset;
        } else {
            if (offsetBuffer != null) {
                return cachedAppendOffset = Unsafe.getUnsafe().getLong(offsetDirectAddr);
            }
            return -1L;
        }
    }

    public void setAppendOffset(long offset) {
        Unsafe.getUnsafe().putLong(offsetDirectAddr, cachedAppendOffset = offset);
    }

    @Override
    public void compact() throws JournalException {
        close();
        try {
            openInternal("rw");
            try {
                long newSize = getAppendOffset() + dataOffset;
                offsetBuffer = ByteBuffers.release(offsetBuffer);
                LOGGER.debug("Compacting %s to %d bytes", this, newSize);
                channel.truncate(newSize).close();
            } catch (IOException e) {
                throw new JournalException("Could not compact %s to %d bytes", e, getFullFileName(), getAppendOffset());
            } finally {
                close();
            }
        } finally {
            open();
        }
    }

    public String getFullFileName() {
        return this.file.getAbsolutePath();
    }

    public void force() {
        int stitchesSize = stitches.size();
        offsetBuffer.force();
        for (int i = 0, buffersSize = buffers.size(); i < buffersSize; i++) {
            MappedByteBuffer b = buffers.get(i);
            if (b != null) {
                b.force();
            }

            if (i < stitchesSize) {
                ByteBufferWrapper s = stitches.get(i);
                if (s != null) {
                    s.getByteBuffer().force();
                }
            }
        }
    }

    private long allocateAddress(long offset, int size) {
        cachedBuffer = getBufferInternal(offset, size);
        cachedBufferLo = offset - cachedBuffer.position();
        cachedBufferHi = cachedBufferLo + cachedBuffer.limit();
        return (cachedAddress = ((DirectBuffer) cachedBuffer).address()) + cachedBuffer.position();
    }

    private MappedByteBuffer getBufferInternal(long offset, int size) {

        int bufferSize = 1 << bitHint;
        int bufferIndex = (int) (offset >>> bitHint);
        long bufferOffset = ((long) bufferIndex) * ((long) bufferSize);
        int bufferPos = (int) (offset - bufferOffset);


        Lists.advance(buffers, bufferIndex);

        MappedByteBuffer buffer = buffers.get(bufferIndex);

        if (buffer != null && buffer.limit() < bufferPos) {
            buffer = ByteBuffers.release(buffer);
        }

        if (buffer == null) {
            buffer = mapBufferInternal(bufferOffset, bufferSize);
            assert bufferSize > 0;
            buffers.set(bufferIndex, buffer);
            switch (mode) {
                case BULK_READ:
                case BULK_APPEND:
                    // for bulk operations unmap all buffers except for current one
                    // this is to prevent OS paging large files.
                    cachedBuffer = null;
                    cachedBufferLo = cachedBufferHi = -1;
                    int ssz = stitches.size();
                    for (int i = bufferIndex - 1; i >= 0; i--) {
                        MappedByteBuffer b = buffers.get(i);
                        if (b != null) {
                            buffers.set(i, ByteBuffers.release(b));
                        }

                        if (i < ssz) {
                            ByteBufferWrapper stitch = stitches.get(i);
                            if (stitch != null) {
                                stitch.release();
                                stitches.set(i, null);
                            }
                        }
                    }
            }
        }

        buffer.position(bufferPos);

        // if the desired size is larger than remaining buffer we need to crate
        // a stitch buffer, which would accommodate the size
        if (buffer.remaining() < size) {

            Lists.advance(stitches, bufferIndex);

            ByteBufferWrapper bufferWrapper = stitches.get(bufferIndex);

            long stitchOffset = bufferOffset + bufferPos;
            if (bufferWrapper != null) {
                // if we already have a stitch for this buffer
                // it could be too small for the size
                // if that's the case - discard the existing stitch and create a larger one.
                if (bufferWrapper.getOffset() != stitchOffset || bufferWrapper.getByteBuffer().limit() < size) {
                    bufferWrapper.release();
                    bufferWrapper = null;
                } else {
                    bufferWrapper.getByteBuffer().rewind();
                }
            }

            if (bufferWrapper == null) {
                bufferWrapper = new ByteBufferWrapper(stitchOffset, mapBufferInternal(stitchOffset, size));
                stitches.set(bufferIndex, bufferWrapper);
            }

            return bufferWrapper.getByteBuffer();
        }
        return buffer;
    }

    private long size() throws JournalException {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new JournalException("Could not get channel size", e);
        }
    }

    void open() throws JournalException {
        String m;
        switch (mode) {
            case READ:
            case BULK_READ:
                m = "r";
                break;
            default:
                m = "rw";
        }
        openInternal(m);
    }

    private void openInternal(String mode) throws JournalException {

        if (file.getParentFile() == null) {
            throw new JournalException("Expected a file: " + file);
        }

        if (!file.getParentFile().exists()) {
            if (!file.getParentFile().mkdirs()) {
                throw new JournalException("Could not create directories: %s", file.getParentFile().getAbsolutePath());
            }
        }

        try {
            this.channel = new RandomAccessFile(file, mode).getChannel();
            if ("r".equals(mode)) {
                this.offsetBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, Math.min(channel.size(), 8));
            } else {
                this.offsetBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, 8);
            }
            offsetBuffer.order(ByteOrder.LITTLE_ENDIAN);
            offsetDirectAddr = ((DirectBuffer) offsetBuffer).address();
        } catch (FileNotFoundException e) {
            throw new JournalNoSuchFileException(e);
        } catch (IOException e) {
            throw new JournalException(e);
        }
    }

    private MappedByteBuffer mapBufferInternal(long offset, int size) {
        long actualOffset = offset + dataOffset;

        try {
            MappedByteBuffer buf;
            switch (mode) {
                case READ:
                case BULK_READ:
                    // make sure size does not extend beyond actual file size, otherwise
                    // java would assume we want to write and throw an exception
                    long sz;
                    if (actualOffset + size > channel.size()) {
                        sz = channel.size() - actualOffset;
                    } else {
                        sz = size;
                    }
                    assert sz > 0;
                    buf = channel.map(FileChannel.MapMode.READ_ONLY, actualOffset, sz);
                    break;
                default:
                    buf = channel.map(FileChannel.MapMode.READ_WRITE, actualOffset, size);
                    break;
            }
            buf.order(ByteOrder.LITTLE_ENDIAN);
            return buf;
        } catch (IOException e) {
            throw new JournalRuntimeException("Failed to memory map: %s", e, file.getAbsolutePath());
        }
    }

    private void unmap() {
        for (int i = 0, buffersSize = buffers.size(); i < buffersSize; i++) {
            MappedByteBuffer b = buffers.get(i);
            if (b != null) {
                ByteBuffers.release(b);
            }
        }
        for (int i = 0, stitchesSize = stitches.size(); i < stitchesSize; i++) {
            ByteBufferWrapper b = stitches.get(i);
            if (b != null) {
                b.release();
            }
        }
        cachedBuffer = null;
        cachedBufferLo = cachedBufferHi = -1;
        buffers.clear();
        stitches.clear();
    }
}
