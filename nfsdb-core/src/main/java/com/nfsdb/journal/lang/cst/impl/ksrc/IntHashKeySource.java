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

package com.nfsdb.journal.lang.cst.impl.ksrc;

import com.nfsdb.journal.collections.DirectIntList;
import com.nfsdb.journal.lang.cst.KeyCursor;
import com.nfsdb.journal.lang.cst.KeySource;
import com.nfsdb.journal.lang.cst.PartitionSlice;
import com.nfsdb.journal.lang.cst.impl.ref.StringRef;

public class IntHashKeySource implements KeySource, KeyCursor {
    private final StringRef column;
    private final DirectIntList values;
    private int bucketCount = -1;
    private int valueIndex;

    public IntHashKeySource(StringRef column, DirectIntList values) {
        this.column = column;
        this.values = values;
    }

    @Override
    public KeyCursor cursor(PartitionSlice slice) {
        if (bucketCount == -1) {
            bucketCount = slice.partition.getJournal().getMetadata().getColumnMetadata(column.value).distinctCountHint;
        }
        this.valueIndex = 0;
        return this;
    }

    @Override
    public boolean hasNext() {
        return valueIndex < values.size();
    }

    @Override
    public int next() {
        return values.get(valueIndex++) % bucketCount;
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public void reset() {
        bucketCount = -1;
    }
}
