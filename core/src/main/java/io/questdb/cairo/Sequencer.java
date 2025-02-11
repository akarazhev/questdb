/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import java.io.Closeable;

public interface Sequencer extends Closeable {
    long NO_TXN = Long.MIN_VALUE;
    String SEQ_DIR = "seq";

    // returns next available txn number after alters the schema
    long addColumn(int columnIndex, CharSequence columnName, int columnType, int walId, long segmentId);

    @Override
    void close();

    // always creates a new wal with an increasing unique id
    WalWriter createWal();

    // returns next available txn number if schema version is the expected one, otherwise returns NO_TXN
    long nextTxn(int expectedSchemaVersion, int walId, long segmentId);

    // returns next available txn number
    long nextTxn(int walId, long segmentId);

    void open();

    // populates the given table descriptor with the current snapshot of metadata and schema version number
    void populateDescriptor(TableDescriptor descriptor);

    // returns next available txn number after alters the schema
    long removeColumn(int columnIndex, int walId, long segmentId);
}
