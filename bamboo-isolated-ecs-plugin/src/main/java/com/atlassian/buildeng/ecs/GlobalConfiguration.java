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

package com.atlassian.buildeng.ecs;

import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.DeregisterTaskDefinitionRequest;
import com.amazonaws.services.ecs.model.ListClustersResult;
import com.atlassian.bamboo.bandana.PlanAwareBandanaContext;
import com.atlassian.bamboo.configuration.AdministrationConfigurationAccessor;
import com.atlassian.bamboo.persister.AuditLogEntry;
import com.atlassian.bamboo.persister.AuditLogMessage;
import com.atlassian.bamboo.persister.AuditLogService;
import com.atlassian.bamboo.user.BambooAuthenticationContext;
import com.atlassian.bandana.BandanaManager;
import com.atlassian.buildeng.ecs.exceptions.ECSException;
import com.atlassian.buildeng.ecs.rest.Config;
import com.atlassian.buildeng.ecs.scheduling.BambooServerEnvironment;
import com.atlassian.buildeng.ecs.scheduling.ECSConfiguration;
import com.atlassian.buildeng.ecs.scheduling.TaskDefinitionRegistrations;
import com.atlassian.buildeng.spi.isolated.docker.Configuration;
import com.atlassian.buildeng.spi.isolated.docker.ConfigurationPersistence;
import com.atlassian.buildeng.spi.isolated.docker.ContainerSizeDescriptor;
import com.atlassian.buildeng.spi.isolated.docker.HostFolderMapping;
import com.atlassian.buildeng.spi.isolated.docker.HostFolderMappingModuleDescriptor;
import com.atlassian.plugin.PluginAccessor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalConfiguration
        implements ECSConfiguration, TaskDefinitionRegistrations.Backend, BambooServerEnvironment {

    // Bandana access keys
    static String BANDANA_CLUSTER_KEY = "com.atlassian.buildeng.ecs.cluster";
    static String BANDANA_DOCKER_MAPPING_KEY = "com.atlassian.buildeng.ecs.docker.config2";
    static String BANDANA_ECS_TASK_MAPPING_KEY = "com.atlassian.buildeng.ecs.docker.task.config";
    static String BANDANA_SIDEKICK_KEY = "com.atlassian.buildeng.ecs.sidekick";
    static String BANDANA_ASG_KEY = "com.atlassian.buildeng.ecs.asg";
    static String BANDANA_LOGGING_DRIVER_KEY = "com.atlassian.buildeng.ecs.loggingDriver";
    static String BANDANA_LOGGING_OPTS_KEY = "com.atlassian.buildeng.ecs.loggingOpts";
    static String BANDANA_ENVS_KEY = "com.atlassian.buildeng.ecs.envVars";
    static String BANDANA_PREEMPTIVE_KEY = "com.atlassian.buildeng.ecs.preemptiveScaling";

    private static final Logger logger = LoggerFactory.getLogger(GlobalConfiguration.class);
    private final BandanaManager bandanaManager;
    private final AdministrationConfigurationAccessor admConfAccessor;
    private final AuditLogService auditLogService;
    private final BambooAuthenticationContext authenticationContext;
    private final PluginAccessor pluginAccessor;
    private final ContainerSizeDescriptor sizeDescriptor;

    public GlobalConfiguration(
            BandanaManager bandanaManager,
            AdministrationConfigurationAccessor admConfAccessor,
            AuditLogService auditLogService,
            BambooAuthenticationContext authenticationContext,
            PluginAccessor pluginAccessor,
            ContainerSizeDescriptor sizeDescriptor) {
        this.bandanaManager = bandanaManager;
        this.admConfAccessor = admConfAccessor;
        this.auditLogService = auditLogService;
        this.authenticationContext = authenticationContext;
        this.pluginAccessor = pluginAccessor;
        this.sizeDescriptor = sizeDescriptor;
    }

    @Override
    public String getTaskDefinitionName() {
        String instanceName = admConfAccessor.getAdministrationConfiguration().getInstanceName();
        // Sanitize as the family for a task definition can only contain certain characters and
        // be of max length 255
        instanceName = instanceName.replaceAll("[^\\w-]", "");
        return instanceName.substring(
                        0, Math.min(instanceName.length(), 255 - Constants.TASK_DEFINITION_SUFFIX.length()))
                + Constants.TASK_DEFINITION_SUFFIX;
    }

    // Constructs a standard de-register request for a standard generated task definition
    private DeregisterTaskDefinitionRequest deregisterTaskDefinitionRequest(Integer revision) {
        return new DeregisterTaskDefinitionRequest().withTaskDefinition(getTaskDefinitionName() + ":" + revision);
    }

    /**
     * Get the repository that is currently configured to be used for the agent sidekick.
     *
     * @return The current sidekick repository
     */
    @Override
    public String getCurrentSidekick() {
        return (String) bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_SIDEKICK_KEY);
    }

    @Override
    public void persistDockerMappingsConfiguration(
            Map<Configuration, Integer> dockerMappings, Map<String, Integer> taskRequestMappings) {
        bandanaManager.setValue(
                PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_DOCKER_MAPPING_KEY, convertToPersisted(dockerMappings));
        bandanaManager.setValue(
                PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_ECS_TASK_MAPPING_KEY, taskRequestMappings);
    }

    // ECS Cluster management

    /**
     * Get the ECS cluster that is currently configured to be used.
     *
     * @return The current cluster name
     */
    @Override
    public String getCurrentCluster() {
        String name = (String) bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_CLUSTER_KEY);
        return name == null ? Constants.DEFAULT_CLUSTER : name;
    }

    /**
     * Get a collection of potential ECS clusters to use.
     *
     * @return The collection of cluster names
     */
    List<String> getValidClusters() throws ECSException {
        try {
            ListClustersResult result = createClient().listClusters();
            return result.getClusterArns().stream()
                    .map((String x) -> x.split("/")[1])
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new ECSException(e);
        }
    }

    /**
     * Get custom logging driver to use with job tasks.
     *
     * @return the custom logging driver or null of none defined.
     */
    @Override
    public String getLoggingDriver() {
        return (String) bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_LOGGING_DRIVER_KEY);
    }

    /**
     * Get custom logging driver options.
     *
     * @return The current cluster nam
     */
    @Override
    public Map<String, String> getLoggingDriverOpts() {
        Map<String, String> map = (Map<String, String>)
                bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_LOGGING_OPTS_KEY);
        return map != null ? map : new HashMap<>();
    }

    @Override
    public Map<String, String> getEnvVars() {
        Map<String, String> map =
                (Map<String, String>) bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_ENVS_KEY);
        return map != null ? map : new HashMap<>();
    }

    // Docker - ECS mapping management

    @Override
    public String getBambooBaseUrl() {
        return admConfAccessor.getAdministrationConfiguration().getBaseUrl();
    }

    public boolean isPreemptiveScaling() {
        Boolean val = (Boolean) bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_PREEMPTIVE_KEY);
        return val != null ? val : false;
    }

    /**
     * Returns a list of Configuration objects that were used to register the given
     * task definition revision.
     *
     * @return All the docker image:identifier pairs this service has registered
     */
    @Override
    public Map<Configuration, Integer> getAllRegistrations() {
        Map<String, Integer> values = (Map<String, Integer>)
                bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_DOCKER_MAPPING_KEY);
        return values == null ? new HashMap<>() : convertFromPersisted(values);
    }

    @Override
    public Map<String, Integer> getAllECSTaskRegistrations() {
        Map<String, Integer> ret = (Map<String, Integer>)
                bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_ECS_TASK_MAPPING_KEY);
        return ret == null ? new HashMap<>() : ret;
    }

    @Override
    public String getCurrentASG() {
        String name = (String) bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_ASG_KEY);
        return name == null ? "" : name;
    }

    @Override
    public String getECSTaskRoleARN() {
        return null;
    }

    @VisibleForTesting
    AmazonECS createClient() {
        return AmazonECSClientBuilder.defaultClient();
    }

    private Map<Configuration, Integer> convertFromPersisted(Map<String, Integer> persisted) {
        final HashMap<Configuration, Integer> newMap = new HashMap<>();
        persisted.forEach((String t, Integer u) -> {
            newMap.put(ConfigurationPersistence.toConfiguration(t), u);
        });
        return newMap;
    }

    private Map<String, Integer> convertToPersisted(Map<Configuration, Integer> val) {
        final HashMap<String, Integer> newMap = new HashMap<>();
        val.forEach((Configuration t, Integer u) -> {
            newMap.put(ConfigurationPersistence.toJson(t).toString(), u);
        });
        return newMap;
    }

    void setConfig(Config config) {
        Preconditions.checkArgument(StringUtils.isNotBlank(config.getAutoScalingGroupName()));
        Preconditions.checkArgument(StringUtils.isNotBlank(config.getEcsClusterName()));
        if (!StringUtils.equals(config.getEcsClusterName(), getCurrentCluster())) {
            auditLogEntry("PBC Cluster", getCurrentCluster(), config.getEcsClusterName());
            bandanaManager.setValue(
                    PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_CLUSTER_KEY, config.getEcsClusterName());
        }
        if (!StringUtils.equals(config.getAutoScalingGroupName(), getCurrentASG())) {
            auditLogEntry("PBC Autoscaling Group", getCurrentASG(), config.getAutoScalingGroupName());
            bandanaManager.setValue(
                    PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_ASG_KEY, config.getAutoScalingGroupName());
        }
        String newSidekick = config.getSidekickImage();
        if (!StringUtils.equals(newSidekick, getCurrentSidekick())) {
            auditLogEntry("PBC Sidekick Image", getCurrentSidekick(), newSidekick);
            bandanaManager.setValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_SIDEKICK_KEY, newSidekick);
        }
        boolean newPremptive = config.isPreemptiveScaling();
        if (isPreemptiveScaling() != newPremptive) {
            auditLogEntry("PBC Preemptive scaling", "" + isPreemptiveScaling(), "" + newPremptive);
            bandanaManager.setValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_PREEMPTIVE_KEY, newPremptive);
        }
        Config.LogConfiguration lc = config.getLogConfiguration();
        String driver = lc != null ? lc.getDriver() : null;
        if (!StringUtils.equals(getLoggingDriver(), driver)) {
            auditLogEntry("PBC Docker Log Driver", getLoggingDriver(), driver);
            if (StringUtils.isBlank(driver)) {
                bandanaManager.removeValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_LOGGING_DRIVER_KEY);
            } else {
                bandanaManager.setValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_LOGGING_DRIVER_KEY, driver);
            }
        }
        bandanaManager.setValue(
                PlanAwareBandanaContext.GLOBAL_CONTEXT,
                BANDANA_LOGGING_OPTS_KEY,
                lc != null ? lc.getOptions() : Collections.emptyMap());
        Map<String, String> newMap = config.getEnvs() != null ? config.getEnvs() : Collections.emptyMap();
        if (!newMap.equals(getEnvVars())) {
            auditLogEntry("PBC Env Variables", Objects.toString(getEnvVars()), Objects.toString(newMap));
        }
        bandanaManager.setValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, BANDANA_ENVS_KEY, newMap);
    }

    private void auditLogEntry(String name, String oldValue, String newValue) {
        AuditLogEntry ent = new AuditLogMessage(
                authenticationContext.getUserName(),
                new Date(),
                null,
                null,
                null,
                null,
                AuditLogEntry.TYPE_FIELD_CHANGE,
                name,
                oldValue,
                newValue);
        auditLogService.log(ent);
    }

    @Override
    public List<HostFolderMapping> getHostFolderMappings() {
        return pluginAccessor.getEnabledModuleDescriptorsByClass(HostFolderMappingModuleDescriptor.class).stream()
                .map((HostFolderMappingModuleDescriptor t) -> t.getModule())
                .collect(Collectors.toList());
    }

    @Override
    public ContainerSizeDescriptor getSizeDescriptor() {
        return sizeDescriptor;
    }
}
