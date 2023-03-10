/*
 * Copyright 2016 - 2017 Atlassian Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.atlassian.buildeng.ecs.rest;

import java.util.List;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings("FieldCanBeLocal")
public final class JobsUsingImageResponse {
    public final List<JobInfo> jobs;

    public JobsUsingImageResponse(@NotNull List<JobInfo> jobs) {
        this.jobs = jobs;
    }

    public static final class JobInfo {
        public final String name;
        public final String key;

        public JobInfo(String name, String key) {
            this.name = name;
            this.key = key;
        }
    }
}
