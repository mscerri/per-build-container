/*
 * Copyright 2015 Atlassian.
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

package com.atlassian.buildeng.isolated.docker;

import com.atlassian.buildeng.spi.isolated.docker.Configuration;
import com.atlassian.bamboo.builder.LifeCycleState;
import com.atlassian.bamboo.buildqueue.ElasticAgentDefinition;
import com.atlassian.bamboo.buildqueue.LocalAgentDefinition;
import com.atlassian.bamboo.buildqueue.PipelineDefinitionVisitor;
import com.atlassian.bamboo.buildqueue.RemoteAgentDefinition;
import com.atlassian.bamboo.buildqueue.manager.AgentManager;
import com.atlassian.bamboo.deployments.events.DeploymentTriggeredEvent;
import com.atlassian.bamboo.deployments.execution.DeploymentContext;
import com.atlassian.bamboo.deployments.execution.events.DeploymentFinishedEvent;
import com.atlassian.bamboo.deployments.results.DeploymentResult;
import com.atlassian.bamboo.deployments.results.service.DeploymentResultService;
import com.atlassian.bamboo.event.agent.AgentRegisteredEvent;
import com.atlassian.bamboo.logger.ErrorUpdateHandler;
import com.atlassian.bamboo.v2.build.BuildContext;
import com.atlassian.bamboo.v2.build.CommonContext;
import com.atlassian.bamboo.v2.build.agent.AgentCommandSender;
import com.atlassian.bamboo.v2.build.agent.BuildAgent;
import com.atlassian.bamboo.v2.build.agent.capability.CapabilitySet;
import com.atlassian.bamboo.v2.build.events.BuildQueuedEvent;
import com.atlassian.bamboo.v2.build.queue.BuildQueueManager;
import com.atlassian.buildeng.isolated.docker.events.DockerAgentFailEvent;
import com.atlassian.buildeng.isolated.docker.events.DockerAgentTimeoutEvent;
import com.atlassian.buildeng.isolated.docker.events.RetryAgentStartupEvent;
import com.atlassian.buildeng.isolated.docker.jmx.JMXAgentsService;
import com.atlassian.buildeng.isolated.docker.reaper.DeleterGraveling;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedAgentService;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerAgentException;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerAgentRequest;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerAgentResult;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerRequestCallback;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * this class is an event listener because preBuildQueuedAction requires restart
 * of bamboo when re-deployed.
 */
public class PreBuildQueuedEventListener {

    private final IsolatedAgentService isolatedAgentService;
    private final Logger LOG = LoggerFactory.getLogger(PreBuildQueuedEventListener.class);
    private final ErrorUpdateHandler errorUpdateHandler;
    private final BuildQueueManager buildQueueManager;
    private final AgentCreationRescheduler rescheduler;
    private final JMXAgentsService jmx;
    private final DeploymentResultService deploymentResultService;
    private final AgentCommandSender agentCommandSender;
    private final AgentManager agentManager;
    private final EventPublisher eventPublisher;
 

    private PreBuildQueuedEventListener(IsolatedAgentService isolatedAgentService,
                                        ErrorUpdateHandler errorUpdateHandler,
                                        BuildQueueManager buildQueueManager,
                                        AgentCreationRescheduler rescheduler,
                                        JMXAgentsService jmx,
                                        DeploymentResultService deploymentResultService,
                                        AgentCommandSender agentCommandSender,
                                        EventPublisher eventPublisher,
                                        AgentManager agentManager) {
        this.isolatedAgentService = isolatedAgentService;
        this.errorUpdateHandler = errorUpdateHandler;
        this.buildQueueManager = buildQueueManager;
        this.rescheduler = rescheduler;
        this.jmx = jmx;
        this.deploymentResultService = deploymentResultService;
        this.agentManager = agentManager;
        this.agentCommandSender = agentCommandSender;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void call(BuildQueuedEvent event) {
        BuildContext buildContext = event.getContext();
        Configuration config = Configuration.forContext(buildContext);
        if (config.isEnabled()) {
            config.copyTo(buildContext.getCurrentResult().getCustomBuildData());
            jmx.incrementQueued();
            retry(new RetryAgentStartupEvent(config, buildContext));
        } else {
            //when a rerun happens and docker agents were disabled.
            Configuration.removeFrom(buildContext.getCurrentResult().getCustomBuildData());
            clearResultCustomData(event.getContext());
        }
    }

    @EventListener
    public void retry(RetryAgentStartupEvent event) {
        //when we arrive here, user could have cancelled the build.
        if (!isStillQueued(event.getContext())) {
            LOG.info("Retrying but {} was already cancelled, aborting. (state:{})", event.getContext().getResultKey().getKey(), event.getContext().getCurrentResult().getLifeCycleState());
            jmx.incrementCancelled();
            return;
        }
        clearResultCustomData(event.getContext());
        
        isolatedAgentService.startAgent(
                new IsolatedDockerAgentRequest(event.getConfiguration(), event.getContext().getResultKey().getKey(), event.getUniqueIdentifier()),
                        new IsolatedDockerRequestCallback() {
                    @Override
                    public void handle(IsolatedDockerAgentResult result) {
                        if (result.isRetryRecoverable()) {
                            LOG.warn("Build {} was not queued but recoverable, retrying.. Error message: {}", event.getContext().getResultKey().getKey(), Joiner.on("\n").join(result.getErrors()));
                            if (rescheduler.reschedule(new RetryAgentStartupEvent(event))) {
                                return;
                            }
                            jmx.incrementTimedOut();
                            eventPublisher.publish(new DockerAgentTimeoutEvent(event.getRetryCount(), event.getContext().getEntityKey()));
                        }
                        //custom items pushed by the implementation, we give it a unique prefix
                        result.getCustomResultData().entrySet().stream().forEach(ent -> {
                            event.getContext().getCurrentResult().getCustomBuildData().put(Constants.RESULT_PREFIX + ent.getKey(), ent.getValue());
                        });
                        if (result.hasErrors()) {
                            String error = Joiner.on("\n").join(result.getErrors()); 
                            terminateBuild(error);
                            errorUpdateHandler.recordError(event.getContext().getEntityKey(), "Build was not queued due to error:" + error);
                        } else {
                            jmx.incrementScheduled();
                        }
                    }

                    @Override
                    public void handle(IsolatedDockerAgentException exception) {
                        terminateBuild(exception.getLocalizedMessage());
                        errorUpdateHandler.recordError(event.getContext().getEntityKey(), "Build was not queued due to error", exception);
                    }

                    private void terminateBuild(String errorMessage) {
                        event.getContext().getCurrentResult().getCustomBuildData().put(Constants.RESULT_ERROR, errorMessage);
                        jmx.incrementFailed();
                        eventPublisher.publish(new DockerAgentFailEvent(errorMessage, event.getContext().getEntityKey()));
                        if (event.getContext() instanceof BuildContext) { 
                            event.getContext().getCurrentResult().setLifeCycleState(LifeCycleState.NOT_BUILT);
                            buildQueueManager.removeBuildFromQueue(event.getContext().getResultKey());
                        } else if (event.getContext() instanceof DeploymentContext) {
                            //TODO not sure how to deal with queued deployments
//                            DeploymentContext dc = (DeploymentContext)event.getContext();
//                            deploymentResultService.updateLifeCycleState(dc.getDeploymentResultId(), LifeCycleState.NOT_BUILT);
                        }
                    }
                });

    }

    private void clearResultCustomData(CommonContext context) {
        //remove any preexisting items when queuing, these are remains of the
        //previous run and can interfere with further processing and are polluting the ui.
        context.getCurrentResult().getCustomBuildData().keySet().stream()
                .filter((String t) -> t.startsWith(Constants.RESULT_PREFIX))
                .forEach((String t) -> {
                    context.getCurrentResult().getCustomBuildData().remove(t);
                });
        context.getCurrentResult().getCustomBuildData().remove(Constants.RESULT_ERROR);
    }

    private boolean isStillQueued(CommonContext context) {
        LifeCycleState state = context.getCurrentResult().getLifeCycleState();
        return LifeCycleState.isPending(state) || LifeCycleState.isQueued(state);
    }
    
    @EventListener
    public void agentRegistered(AgentRegisteredEvent event) {
        event.getAgent().accept(new PipelineDefinitionVisitor() {
            @Override
            public void visitElastic(ElasticAgentDefinition pipelineDefinition) {
            }

            @Override
            public void visitLocal(LocalAgentDefinition pipelineDefinition) {
            }

            @Override
            public void visitRemote(RemoteAgentDefinition pipelineDefinition) {
                CapabilitySet cs = pipelineDefinition.getCapabilitySet();
                if (cs != null && cs.getCapability(Constants.CAPABILITY_RESULT) != null) {
                    jmx.incrementActive();
                }
            }
        });
    }
    
    
    //2 events related to deployment environments
    @EventListener
    public void deploymentTriggered(DeploymentTriggeredEvent event) {
        LOG.info("deployment triggered event:" + event);
        DeploymentContext buildContext = event.getContext();
        Configuration config = Configuration.forContext(buildContext);
        if (config.isEnabled()) {
            config.copyTo(buildContext.getCurrentResult().getCustomBuildData());
            jmx.incrementQueued();
            retry(new RetryAgentStartupEvent(config, buildContext));
        }
    }
    
    @EventListener
    public void deploymentFinished(DeploymentFinishedEvent event) {
        LOG.info("deployment finished event:" + event);
        DeploymentResult dr = deploymentResultService.getDeploymentResult(event.getDeploymentResultId());
        Configuration config = Configuration.forDeploymentResult(dr);
        if (config.isEnabled()) {
            BuildAgent agent = dr != null ? dr.getAgent() : null;
            if (agent != null) {
                DeleterGraveling.stopAndRemoveAgentRemotely(agent, agentManager, agentCommandSender);
            }
        }
    }
}
