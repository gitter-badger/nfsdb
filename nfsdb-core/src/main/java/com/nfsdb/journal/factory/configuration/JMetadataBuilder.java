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

package com.nfsdb.journal.factory.configuration;

import com.nfsdb.journal.PartitionType;

import java.io.File;

public interface JMetadataBuilder<T> {
    JMetadataBuilder<T> partitionBy(PartitionType type);

    JMetadataBuilder<T> recordCountHint(int count);

    String getLocation();

    JournalMetadata<T> build();

    JMetadataBuilder<T> location(String location);

    JMetadataBuilder<T> location(File path);
}
