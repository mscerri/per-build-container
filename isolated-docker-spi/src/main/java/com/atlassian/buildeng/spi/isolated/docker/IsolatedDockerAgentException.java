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

public class IsolatedDockerAgentException extends Exception {

    public IsolatedDockerAgentException(Throwable cause) {
        super(cause);
    }

    public IsolatedDockerAgentException(String message) {
        super(message);
    }

    public IsolatedDockerAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
