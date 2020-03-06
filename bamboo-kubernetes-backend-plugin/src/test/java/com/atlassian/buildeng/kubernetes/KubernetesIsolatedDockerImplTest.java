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

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import com.atlassian.bamboo.plan.PlanKey;
import com.atlassian.bamboo.plan.PlanKeys;
import com.atlassian.buildeng.kubernetes.jmx.KubeJmxService;
import com.atlassian.buildeng.spi.isolated.docker.DefaultContainerSizeDescriptor;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerAgentException;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerAgentRequest;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerAgentResult;
import com.atlassian.buildeng.spi.isolated.docker.IsolatedDockerRequestCallback;
import com.atlassian.sal.api.scheduling.PluginScheduler;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesIsolatedDockerImplTest {
    @Mock
    GlobalConfiguration globalConfiguration;
    @Mock
    KubeJmxService kubeJmxService;
    @Mock
    PluginScheduler pluginScheduler;
    @Mock
    ExecutorService executor;
    @Mock
    ExternalIdService externalIdService;
    @Mock
    KubernetesClient kubernetesClient;

    @InjectMocks
    KubernetesIsolatedDockerImpl kubernetesIsolatedDocker;

    @Before
    public void setup() throws IOException {
        when(globalConfiguration.getBambooBaseUrl()).thenReturn("http://test-bamboo.com");
        when(globalConfiguration.getSizeDescriptor()).thenReturn(new DefaultContainerSizeDescriptor());
        when(globalConfiguration.getCurrentSidekick()).thenReturn("sidekickImage");

        String iamRequestTemplate = FileUtils.readFileToString(new File(this.getClass().getResource("/iamRequestTemplate.yaml").getFile()), "UTF-8");
        String podRequestTemplate = FileUtils.readFileToString(new File(this.getClass().getResource("/podTemplate.yaml").getFile()), "UTF-8");

        when(globalConfiguration.getPodTemplateAsString()).thenReturn(podRequestTemplate);
        when(globalConfiguration.getBandanaIamRequestTemplateAsString()).thenReturn(iamRequestTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testContainersMergedByName() {
        Yaml yaml =  new Yaml(new SafeConstructor());
        String ts = "apiVersion: v1\n"
              + "kind: Pod\n"
              + "metadata:\n"
              + "  name: aws-cli\n"
              + "spec:\n"
              + "  hostAliases:\n"
              + "    - ip: 192.168.1.1\n"
              + "      hostnames:\n"
              + "          - wifi\n"  
              + "    - ip: 127.0.0.1\n"
              + "      hostnames:\n"
              + "          - me.local\n"  
              + "  containers:\n"
              + "    - name: main\n"
              + "      volumeMounts:\n"
              + "          - name: secrets\n"
              + "            mountPath: /root/.aws\n"
              + "    - name: myContainer2\n"
              + "      image: xueshanf/awscli:latest\n"
              + "  restartPolicy: Never\n"
              + "  volumes:\n"
              + "    - name: secrets\n"
              + "      secret:\n"
              + "        secretName: bitbucket-bamboo\n";
        String os =
                "metadata:\n"
              + "    namespace: buildeng\n"
              + "    annotations:\n"
              + "        iam.amazonaws.com/role: arn:aws:iam::123456678912:role/staging-bamboo\n"
              + "spec:\n"
              + "  hostAliases:\n"
              + "    - ip: 100.100.0.1\n"
              + "      hostnames:\n"
              + "          - remote\n"  
              + "    - ip: 127.0.0.1\n"
              + "      hostnames:\n"
              + "          - bamboo-agent\n"  
              + "  containers:\n"
              + "    - name: main\n"
              + "      image: xueshanf/awscli:latest\n"
              + "    - name: myContainer3\n"
              + "      image: xueshanf/awscli:latest\n"
              + "  volumes:\n"
              + "    - name: myvolume\n";

        Map<String, Object> template = (Map<String, Object>) yaml.load(ts);
        Map<String, Object> overrides = (Map<String, Object>) yaml.load(os);
        Map<String, Object> merged = KubernetesIsolatedDockerImpl.mergeMap(template, overrides);
        Map<String, Object> spec = (Map<String, Object>) merged.get("spec");
        assertEquals(3, ((Collection) spec.get("containers")).size());
        assertEquals(2, ((Collection) spec.get("volumes")).size());
        assertEquals(3, ((Collection) spec.get("hostAliases")).size());

        List<Map<String, Object>> containers = ((List<Map<String, Object>>) spec.get("containers"));
        assertEquals(1, containers.stream().filter(c -> c.containsValue("main")).collect(toList()).size());
        for (Map<String, Object> container : containers) {
            if (container.containsValue("main")) {
                assertNotEquals(null, container.get("image"));
                assertNotEquals(null, container.get("volumeMounts"));
            }
        }
        List<Map<String, Object>> hostAliases = ((List<Map<String, Object>>) spec.get("hostAliases"));
        assertEquals(2, hostAliases.stream()
                .filter((Map<String, Object> t) -> "127.0.0.1".equals(t.get("ip")))
                .flatMap((Map<String, Object> t) -> ((List<String>) t.get("hostnames")).stream())
                .count());
        
    }

    @Test
    public void pbcShouldRetryOnExceedingQuota() {
        KubernetesClient.KubectlException ke = new KubernetesClient.KubectlException("exceeded quota");
        final AtomicBoolean retry = new AtomicBoolean(false);
        IsolatedDockerRequestCallback callback = new IsolatedDockerRequestCallback() {
            @Override
            public void handle(IsolatedDockerAgentResult result) {
                retry.set(result.isRetryRecoverable());
            }

            @Override
            public void handle(IsolatedDockerAgentException exception) {

            }
        };
        kubernetesIsolatedDocker.handleKubeCtlException(callback, ke);
        assertTrue("PBC should retry on exceeding kube quota", retry.get());
    }

    @Test
    public void testExternalIdForPlan() {
        IsolatedDockerAgentRequest request = new IsolatedDockerAgentRequest(null,
            "TEST-PLAN-JOB1",
            UUID.fromString("379ad7b0-b4f5-4fae-914b-070e9442c0a9"),
            0, "bk", 0, true);

        when(externalIdService.getExternalId(any(PlanKey.class))).thenReturn("mock-external-id");

        kubernetesIsolatedDocker.getExternalId(request);
        verify(externalIdService).getExternalId(PlanKeys.getPlanKey("TEST-PLAN-JOB1"));
    }

    @Test
    public void testExternalIdForDeployment() {
        IsolatedDockerAgentRequest request = new IsolatedDockerAgentRequest(null,
            "111-222-333",
            UUID.fromString("379ad7b0-b4f5-4fae-914b-070e9442c0a9"),
            0, "bk", 0, false);

        when(externalIdService.getExternalId(any(Long.class))).thenReturn("mock-external-id");
        kubernetesIsolatedDocker.getExternalId(request);
        verify(externalIdService).getExternalId(111L);
    }
}
