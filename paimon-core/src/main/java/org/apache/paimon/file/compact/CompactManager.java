/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.file.compact;

import org.apache.paimon.file.io.DataFileMeta;

import java.io.Closeable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Manager to submit compaction task. */
public interface CompactManager extends Closeable {

    /** Should wait compaction finish. */
    boolean shouldWaitCompaction();

    /** Add a new file. */
    void addNewFile(DataFileMeta file);

    List<DataFileMeta> allFiles();

    /**
     * Trigger a new compaction task.
     *
     * @param fullCompaction if caller needs a guaranteed full compaction
     */
    void triggerCompaction(boolean fullCompaction);

    /** Get compaction result. Wait finish if {@code blocking} is true. */
    Optional<CompactResult> getCompactionResult(boolean blocking)
            throws ExecutionException, InterruptedException;

    /** Cancel currently running compaction task. */
    void cancelCompaction();
}