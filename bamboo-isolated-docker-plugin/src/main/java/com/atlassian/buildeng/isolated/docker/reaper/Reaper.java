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

package com.atlassian.buildeng.isolated.docker.reaper;

import com.atlassian.bamboo.buildqueue.manager.AgentManager;
import com.atlassian.bamboo.plan.ExecutableAgentsHelper;
import com.atlassian.bamboo.plan.cache.CachedPlanManager;
import com.atlassian.bamboo.v2.build.agent.BuildAgent;
import com.atlassian.bamboo.v2.build.queue.BuildQueueManager;
import com.atlassian.buildeng.isolated.docker.AgentRemovals;
import com.atlassian.sal.api.lifecycle.LifecycleAware;
import com.atlassian.sal.api.scheduling.PluginScheduler;
import java.time.Duration;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class Reaper implements LifecycleAware {
    private final PluginScheduler pluginScheduler;
    private final ExecutableAgentsHelper executableAgentsHelper;
    private final AgentManager agentManager;
    private final AgentRemovals agentRemovals;
    private final BuildQueueManager buildQueueManager;
    private final CachedPlanManager cachedPlanManager;
    
    static long   REAPER_THRESHOLD_MILLIS = Duration.ofMinutes(5).toMillis(); //Reap agents if they're older than 5 minutes
    static long   REAPER_INTERVAL_MILLIS  =  30000L; //Reap once every 30 seconds
    static String REAPER_KEY = "isolated-docker-reaper";
    static String REAPER_AGENT_MANAGER_KEY = "reaper-agent-manager";
    static String REAPER_AGENTS_HELPER_KEY = "reaper-agents-helper";
    static String REAPER_REMOVALS_KEY = "reaper-agent-removals";
    static String REAPER_DEATH_LIST = "reaper-death-list";
    static String REAPER_CACHEDPLANMANAGER_KEY = "reaper-cached-plan-manager";
    static String REAPER_BUILDQUEUEMANAGER_KEY = "reaper-build-queue-manager";
    

    public Reaper(PluginScheduler pluginScheduler, ExecutableAgentsHelper executableAgentsHelper, 
            AgentManager agentManager, AgentRemovals agentRemovals,
            BuildQueueManager buildQueueManager, CachedPlanManager cachedPlanManager) {
        this.pluginScheduler = pluginScheduler;
        this.executableAgentsHelper = executableAgentsHelper;
        this.agentManager = agentManager;
        this.agentRemovals = agentRemovals;
        this.buildQueueManager = buildQueueManager;
        this.cachedPlanManager = cachedPlanManager;
    }

    @Override
    public void onStart() {
        Map<String, Object> data = new HashMap<>();
        data.put(REAPER_AGENT_MANAGER_KEY, agentManager);
        data.put(REAPER_AGENTS_HELPER_KEY, executableAgentsHelper);
        data.put(REAPER_REMOVALS_KEY, agentRemovals);
        data.put(REAPER_BUILDQUEUEMANAGER_KEY, buildQueueManager);
        data.put(REAPER_CACHEDPLANMANAGER_KEY, cachedPlanManager);
        data.put(REAPER_DEATH_LIST, new ArrayList<BuildAgent>());
        pluginScheduler.scheduleJob(REAPER_KEY,ReaperJob.class, data, new Date(), Reaper.REAPER_INTERVAL_MILLIS);
    }

    @Override
    public void onStop() {
        pluginScheduler.unscheduleJob(REAPER_KEY);
    }
}
