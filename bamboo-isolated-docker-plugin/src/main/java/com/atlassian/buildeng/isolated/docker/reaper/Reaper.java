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

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

import com.atlassian.bamboo.buildqueue.manager.AgentManager;
import com.atlassian.bamboo.plan.ExecutableAgentsHelper;
import com.atlassian.buildeng.isolated.docker.AgentRemovals;
import com.atlassian.buildeng.isolated.docker.UnmetRequirements;
import com.atlassian.buildeng.isolated.docker.scheduler.SchedulerUtils;
import com.atlassian.plugin.spring.scanner.annotation.component.BambooComponent;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.sal.api.lifecycle.LifecycleAware;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@BambooComponent
@ExportAsService({Reaper.class, LifecycleAware.class})
public class Reaper implements LifecycleAware {
    private static final Logger logger = LoggerFactory.getLogger(Reaper.class);

    private final Scheduler scheduler;
    private final ExecutableAgentsHelper executableAgentsHelper;
    private final AgentManager agentManager;
    private final AgentRemovals agentRemovals;
    private final UnmetRequirements unmetRequirements;
    // BUILDENG-12799 Reap agents if they're older than 40 minutes, see the issue to learn why the number is so high.
    static long REAPER_THRESHOLD_MILLIS = Duration.ofMinutes(40).toMillis();
    static long REAPER_INTERVAL_MILLIS = 30000L; // Reap once every 30 seconds
    static JobKey REAPER_KEY = JobKey.jobKey("isolated-docker-reaper");
    static String REAPER_AGENT_MANAGER_KEY = "reaper-agent-manager";
    static String REAPER_AGENTS_HELPER_KEY = "reaper-agents-helper";
    static String REAPER_REMOVALS_KEY = "reaper-agent-removals";
    static String REAPER_UNMET_KEY = "reaper-unmet-requirements";

    @Inject
    public Reaper(
            Scheduler scheduler,
            ExecutableAgentsHelper executableAgentsHelper,
            AgentManager agentManager,
            AgentRemovals agentRemovals,
            UnmetRequirements unmetRequirements) {
        this.scheduler = scheduler;
        this.executableAgentsHelper = executableAgentsHelper;
        this.agentManager = agentManager;
        this.agentRemovals = agentRemovals;
        this.unmetRequirements = unmetRequirements;
    }

    @Override
    public void onStart() {
        SchedulerUtils schedulerUtils = new SchedulerUtils(scheduler, logger);
        List<JobKey> previousJobKeys = Collections.singletonList(REAPER_KEY);
        logger.info(
                "PBC Isolated Docker plugin started. Checking that jobs from a prior instance of the plugin are not still running.");
        schedulerUtils.awaitPreviousJobExecutions(previousJobKeys);

        JobDataMap data = new JobDataMap();
        data.put(REAPER_AGENT_MANAGER_KEY, agentManager);
        data.put(REAPER_AGENTS_HELPER_KEY, executableAgentsHelper);
        data.put(REAPER_REMOVALS_KEY, agentRemovals);
        data.put(REAPER_UNMET_KEY, unmetRequirements);

        Trigger reaperTrigger = newTrigger()
                .startNow()
                .withSchedule(simpleSchedule()
                        .withIntervalInMilliseconds(REAPER_INTERVAL_MILLIS)
                        .repeatForever())
                .build();
        JobDetail reaperJob = newJob(ReaperJob.class)
                .withIdentity(REAPER_KEY)
                .usingJobData(data)
                .build();
        try {
            scheduler.scheduleJob(reaperJob, reaperTrigger);
        } catch (SchedulerException e) {
            logger.error("Unable to schedule ReaperJob", e);
        }
    }

    @Override
    public void onStop() {
        try {
            boolean reaperJobDeletion = scheduler.deleteJob(REAPER_KEY);
            if (!reaperJobDeletion) {
                logger.warn("Was not able to delete Reaper job. Was it already deleted?");
            }
        } catch (SchedulerException e) {
            logger.error("Reaper being stopped but unable to delete ReaperJob", e);
        }
    }
}
