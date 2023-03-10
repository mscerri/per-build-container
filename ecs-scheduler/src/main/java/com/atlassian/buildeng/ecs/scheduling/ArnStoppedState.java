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

package com.atlassian.buildeng.ecs.scheduling;

// there is a copy of this class in com.atlassian.buildeng.ecs.remote.rest package
// any change to this class will have consequences to REST serialization/deserialization
public class ArnStoppedState {
    String arn;
    String containerArn;
    String reason;

    public ArnStoppedState(String arn, String containerArn, String reason) {
        this.arn = arn;
        this.reason = reason;
        this.containerArn = containerArn;
    }

    public String getArn() {
        return arn;
    }

    public String getReason() {
        return reason;
    }

    public String getContainerArn() {
        return containerArn;
    }
}
