/*
 * Copyright 2018 Atlassian Pty Ltd.
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

package com.atlassian.buildeng.isolated.docker.handler;

import com.atlassian.bamboo.collections.ActionParametersMap;
import com.atlassian.bamboo.deployments.configuration.CustomEnvironmentConfigPlugin;
import java.util.Map;
import java.util.Optional;

/**
 * A dummy implementation not doing anything, just carrier of CustomEnvironmentConfigPluginExporter implementation
 * in module declaration.
 */
public class CustomEnvironmentConfigPluginImpl implements CustomEnvironmentConfigPlugin {

    @Override
    public void populateContextForEdit(Optional<Map<String, String>> pluginConfig, Map<String, Object> context) {
    }

    @Override
    public void populateEnvironmentPluginConfig(Map<String, String> pluginConfig, ActionParametersMap parametersMap) {
    }
    
}