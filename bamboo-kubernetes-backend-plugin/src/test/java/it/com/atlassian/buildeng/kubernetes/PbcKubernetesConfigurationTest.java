/*
 * Copyright 2022 Atlassian Pty Ltd.
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

package it.com.atlassian.buildeng.kubernetes;

import static com.atlassian.pageobjects.elements.query.Conditions.forSupplier;
import static com.atlassian.pageobjects.elements.query.Poller.waitUntilTrue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isEmptyOrNullString;

import com.atlassian.bamboo.testutils.user.TestUser;
import com.atlassian.buildeng.kubernetes.pageobject.GenericKubernetesConfigPage;
import com.atlassian.pageobjects.elements.timeout.TimeoutType;
import org.junit.Test;

public class PbcKubernetesConfigurationTest extends AbstractPbcTest {
    @Test
    public void adminCanSaveKubernetesConfiguration() {
        bamboo.fastLogin(TestUser.ADMIN);
        GenericKubernetesConfigPage genericConfigPage = bamboo.visit(GenericKubernetesConfigPage.class);
        genericConfigPage.setSidekickImage("docker.atl-paas.net/sox/buildeng/bamboo-agent-sidekick:latest");
        genericConfigPage.save();
        waitUntilTrue(forSupplier(timeouts.timeoutFor(TimeoutType.SLOW_PAGE_LOAD), genericConfigPage::isSaved));
        assertThat(genericConfigPage.getErrors(), isEmptyOrNullString());
    }
}
