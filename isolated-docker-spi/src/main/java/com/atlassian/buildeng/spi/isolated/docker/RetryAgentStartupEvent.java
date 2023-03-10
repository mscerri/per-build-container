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

package com.atlassian.buildeng.spi.isolated.docker;

import com.atlassian.bamboo.v2.build.CommonContext;
import com.atlassian.event.api.AsynchronousPreferred;
import java.util.UUID;

/**
 * Event to schedule docker agent creation.
 *
 * @author mkleint
 */
@AsynchronousPreferred
public final class RetryAgentStartupEvent {

    private final int retryCount;
    private final CommonContext context;
    private final Configuration configuration;
    private final UUID uniqueIdentifier;

    public RetryAgentStartupEvent(
            Configuration configuration, CommonContext context, int retryCount, UUID existingUuid) {
        this.configuration = configuration;
        this.context = context;
        this.retryCount = retryCount;
        this.uniqueIdentifier = existingUuid;
    }

    public RetryAgentStartupEvent(Configuration configuration, CommonContext context) {
        this(configuration, context, 0, UUID.randomUUID());
    }

    public RetryAgentStartupEvent(RetryAgentStartupEvent previousEvent) {
        this(
                previousEvent.getConfiguration(),
                previousEvent.getContext(),
                previousEvent.getRetryCount() + 1,
                previousEvent.uniqueIdentifier);
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public CommonContext getContext() {
        return context;
    }

    public UUID getUniqueIdentifier() {
        return uniqueIdentifier;
    }
}
