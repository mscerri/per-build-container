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

import com.atlassian.bamboo.build.BuildDefinition;
import com.atlassian.bamboo.deployments.configuration.service.EnvironmentCustomConfigService;
import com.atlassian.bamboo.deployments.environments.Environment;
import com.atlassian.bamboo.deployments.execution.DeploymentContext;
import com.atlassian.bamboo.deployments.results.DeploymentResult;
import com.atlassian.bamboo.plan.cache.ImmutableJob;
import com.atlassian.bamboo.resultsummary.ResultsSummary;
import com.atlassian.bamboo.task.TaskDefinition;
import com.atlassian.bamboo.task.runtime.RuntimeTaskDefinition;
import com.atlassian.bamboo.v2.build.BuildContext;
import com.atlassian.bamboo.v2.build.CommonContext;
import com.atlassian.bamboo.ww2.actions.build.admin.create.BuildConfiguration;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;

public class AccessConfiguration {

    // XXX interplugin dependency
    // these things can never ever change value, because they end up as part of export
    private static final String IMPL_PLUGIN_KEY = "com.atlassian.buildeng.bamboo-isolated-docker-plugin";
    private static final String ENV_MODULE = "pbcEnvironment";
    private static final String DOCKERTASK_MODULE = "dockertask";

    /**
     * Constructs Configuration object for given key value pair.
     * Assumes the keys relating to jobs/environments, not tasks.
     */
    @Nonnull
    public static Configuration forMap(@Nonnull Map<String, String> cc) {
        String role = cc.getOrDefault(Configuration.DOCKER_AWS_ROLE, null);
        if (role != null && "".equals(role)) {
            role = null;
        }
        String architecture = cc.getOrDefault(Configuration.DOCKER_ARCHITECTURE, null);
        if (StringUtils.isBlank(architecture)) {
            architecture = null;
        }
        return ConfigurationBuilder.create(cc.getOrDefault(Configuration.DOCKER_IMAGE, ""))
                .withEnabled(Boolean.parseBoolean(cc.getOrDefault(Configuration.ENABLED_FOR_JOB, "false")))
                .withImageSize(Configuration.ContainerSize.valueOf(
                        cc.getOrDefault(Configuration.DOCKER_IMAGE_SIZE, Configuration.ContainerSize.REGULAR.name())))
                .withExtraContainers(ConfigurationPersistence.fromJsonStringToExtraContainers(
                        cc.getOrDefault(Configuration.DOCKER_EXTRA_CONTAINERS, "[]")))
                .withFeatureFlags(ConfigurationPersistence.fromJsonStringToFeatureFlags(
                        cc.getOrDefault(Configuration.DOCKER_FEATURE_FLAGS, "[]")))
                .withAwsRole(role)
                .withArchitecture(architecture)
                .build();
    }

    /**
     * Constructs Configuration object for given CommonContext.
     */
    public static Configuration forContext(@Nonnull CommonContext context) {
        if (context instanceof BuildContext) {
            return forBuildContext((BuildContext) context);
        }
        if (context instanceof DeploymentContext) {
            return forDeploymentContext((DeploymentContext) context);
        }
        throw new IllegalStateException(
                "Unknown Common Context subclass:" + context.getClass().getName());
    }

    @Nonnull
    private static Configuration forDeploymentContext(@Nonnull DeploymentContext context) {
        for (RuntimeTaskDefinition task : context.getRuntimeTaskDefinitions()) {
            Map<String, String> map = context.getPluginConfigMap(IMPL_PLUGIN_KEY + ":" + ENV_MODULE);
            if (!map.isEmpty()) {
                // not sure this condition is 100% reliable, when enabling and disabling
                // the docker tab data will retain some config.
                return forMap(map);
            }
            // XXX interplugin dependency
            if ((IMPL_PLUGIN_KEY + ":" + DOCKERTASK_MODULE).equals(task.getPluginKey())) {
                return forTaskConfiguration(task);
            }
        }
        return ConfigurationBuilder.create("").withEnabled(false).build();
    }

    /**
     * Constructs Configuration object for given BuildConfiguration.
     */
    @Nonnull
    public static Configuration forBuildConfiguration(@Nonnull BuildConfiguration config) {
        return ConfigurationBuilder.create(config.getString(Configuration.DOCKER_IMAGE))
                .withEnabled(config.getBoolean(Configuration.ENABLED_FOR_JOB))
                .withImageSize(Configuration.ContainerSize.valueOf(
                        config.getString(Configuration.DOCKER_IMAGE_SIZE, Configuration.ContainerSize.REGULAR.name())))
                .withExtraContainers(ConfigurationPersistence.fromJsonStringToExtraContainers(
                        config.getString(Configuration.DOCKER_EXTRA_CONTAINERS, "[]")))
                .withAwsRole(config.getString(Configuration.DOCKER_AWS_ROLE))
                .withArchitecture(config.getString(Configuration.DOCKER_ARCHITECTURE))
                .withFeatureFlags(ConfigurationPersistence.fromJsonStringToFeatureFlags(
                        config.getString(Configuration.DOCKER_FEATURE_FLAGS, "[]")))
                .build();
    }

    @Nonnull
    private static Configuration forBuildContext(@Nonnull BuildContext context) {
        Map<String, String> cc = context.getBuildDefinition().getCustomConfiguration();
        return forMap(cc);
    }

    /**
     * Constructs Configuration object for given ResultsSummary.
     */
    public static Configuration forBuildResultSummary(ResultsSummary summary) {
        Map<String, String> cc = summary.getCustomBuildData();
        return forMap(cc);
    }

    /**
     * Constructs Configuration object for given DeploymentResult.
     */
    public static Configuration forDeploymentResult(DeploymentResult dr) {
        return forMap(dr.getCustomData());
    }

    /**
     * Constructs Configuration object for given TaskDefinition.
     */
    @Nonnull
    public static Configuration forTaskConfiguration(@Nonnull TaskDefinition taskDefinition) {
        Map<String, String> cc = taskDefinition.getConfiguration();
        return ConfigurationBuilder.create(cc.getOrDefault(Configuration.TASK_DOCKER_IMAGE, ""))
                .withEnabled(taskDefinition.isEnabled())
                .withImageSize(Configuration.ContainerSize.valueOf(cc.getOrDefault(
                        Configuration.TASK_DOCKER_IMAGE_SIZE, Configuration.ContainerSize.REGULAR.name())))
                .withExtraContainers(ConfigurationPersistence.fromJsonStringToExtraContainers(
                        cc.getOrDefault(Configuration.TASK_DOCKER_EXTRA_CONTAINERS, "[]")))
                .withAwsRole(cc.getOrDefault(Configuration.TASK_DOCKER_AWS_ROLE, null))
                .withArchitecture(cc.getOrDefault(Configuration.TASK_DOCKER_ARCHITECTURE, null))
                .withFeatureFlags(ConfigurationPersistence.fromJsonStringToFeatureFlags(
                        cc.getOrDefault(Configuration.DOCKER_FEATURE_FLAGS, "[]")))
                .build();
    }

    /**
     * Constructs Configuration object for given Job.
     */
    public static Configuration forJob(ImmutableJob job) {
        return forBuildDefinition(job.getBuildDefinition());
    }

    /**
     * Constructs Configuration object for given BuildDefinition.
     */
    public static Configuration forBuildDefinition(BuildDefinition buildDefinition) {
        Map<String, String> cc = buildDefinition.getCustomConfiguration();
        return forMap(cc);
    }

    /**
     * Constructs Configuration object for given Environment.
     */
    public static Configuration forEnvironment(
            Environment environment, EnvironmentCustomConfigService environmentCustomConfigService) {
        return forMap(environmentCustomConfigService
                .getEnvironmentPluginConfig(environment.getId())
                .getOrDefault(IMPL_PLUGIN_KEY + ":" + ENV_MODULE, Collections.emptyMap()));
    }
}
