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

package com.nfsdb.journal.lang.cst.impl.jsrc;

import com.nfsdb.journal.Journal;
import com.nfsdb.journal.collections.AbstractImmutableIterator;
import com.nfsdb.journal.lang.cst.*;

public class JournalSourceImpl extends AbstractImmutableIterator<JournalEntry> implements JournalSource {
    private final PartitionSource partitionSource;
    private final RowSource rowSource;
    private final JournalEntry item = new JournalEntry();
    private RowCursor cursor;

    public JournalSourceImpl(PartitionSource partitionSource, RowSource rowSource) {
        this.partitionSource = partitionSource;
        this.rowSource = rowSource;
    }

    @Override
    public boolean hasNext() {
        return (cursor != null && cursor.hasNext()) || nextSlice();
    }

    @Override
    public JournalEntry next() {
        item.rowid = cursor.next();
        return item;
    }

    @Override
    public void reset() {
        partitionSource.reset();
        rowSource.reset();
        cursor = null;
    }

    @Override
    public Journal getJournal() {
        return partitionSource.getJournal();
    }

    @SuppressWarnings("unchecked")
    private boolean nextSlice() {
        do {
            if (partitionSource.hasNext()) {
                PartitionSlice slice = partitionSource.next();
                cursor = rowSource.cursor(slice);

                if (cursor == null) {
                    return false;
                }

                item.partition = slice.partition;
            } else {
                return false;
            }
        } while (!cursor.hasNext());

        return true;
    }
}
