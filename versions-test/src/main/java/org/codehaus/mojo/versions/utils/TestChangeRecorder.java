package org.codehaus.mojo.versions.utils;

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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import javax.inject.Named;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.codehaus.mojo.versions.api.change.VersionChange;
import org.codehaus.mojo.versions.api.recording.ChangeRecord;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;

@Named("test")
public class TestChangeRecorder implements ChangeRecorder {
    private final List<VersionChange> changes = new LinkedList<>();

    @Override
    public void recordChange(ChangeRecord changeRecord) {
        changes.add(changeRecord.getVersionChange());
    }

    @Override
    public void writeReport(Path outputPath) {}

    public List<VersionChange> getChanges() {
        return changes;
    }

    public Map<String, ChangeRecorder> asTestMap() {
        HashMap<String, ChangeRecorder> map = new HashMap<>();
        map.put("none", this);
        return map;
    }
}
