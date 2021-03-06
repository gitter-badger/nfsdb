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

package com.nfsdb.journal.query.api;

import com.nfsdb.journal.UnorderedResultSet;
import com.nfsdb.journal.exceptions.JournalException;
import org.joda.time.Interval;

public interface QueryHeadBuilder<T> {

    QueryHeadBuilder<T> limit(Interval interval);

    QueryHeadBuilder<T> limit(long minRowID);

    QueryHeadBuilder<T> filter(String symbol, String value);

    QueryHeadBuilder<T> strict(boolean strict);

    void resetFilter();

    UnorderedResultSet<T> asResultSet() throws JournalException;

}
