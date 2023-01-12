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

package com.atlassian.buildeng.kubernetes;

import com.atlassian.bamboo.configuration.GlobalAdminAction;
import com.atlassian.bamboo.persister.AuditLogService;
import com.atlassian.bamboo.user.BambooAuthenticationContext;
import com.atlassian.bandana.BandanaManager;
import com.atlassian.buildeng.isolated.docker.GlobalConfiguration;
import javax.inject.Inject;

public class ConfigurationAction extends GlobalAdminAction {

    private final GlobalConfiguration globalConfiguration;

    @Inject
    public ConfigurationAction(
            BandanaManager bandanaManager,
            AuditLogService auditLogService,
            BambooAuthenticationContext authenticationContext) {
        this.globalConfiguration = new GlobalConfiguration(bandanaManager, auditLogService, authenticationContext);
    }

    public boolean isShowAwsSpecificFields() {
        return GlobalConfiguration.VENDOR_AWS.equals(globalConfiguration.getVendor());
    }
}
