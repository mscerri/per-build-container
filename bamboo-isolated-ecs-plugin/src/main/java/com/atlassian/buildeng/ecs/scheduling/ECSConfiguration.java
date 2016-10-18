/*
 * Copyright 2016 Atlassian.
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

package com.atlassian.buildeng.ecs.scheduling;

import com.atlassian.buildeng.ecs.exceptions.ECSException;
import com.atlassian.buildeng.ecs.exceptions.ImageAlreadyRegisteredException;
import com.atlassian.buildeng.spi.isolated.docker.Configuration;
import java.util.Map;

public interface ECSConfiguration {
    String getCurrentCluster();
    String getCurrentASG();
    String getCurrentSidekick();
    String getTaskDefinitionName();
    String getBambooBaseUrl();
    String getLoggingDriver();
    Map<String, String> getLoggingDriverOpts();
    Map<String, String> getEnvVars();

    //TODO eventually should be moved out elsewhere
    int findTaskRegistrationVersion(Configuration configuration);
    int registerDockerImage(Configuration configuration) throws ImageAlreadyRegisteredException, ECSException;


}
