/*
 * Copyright 2021 Atlassian Pty Ltd.
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

import com.atlassian.plugin.spring.scanner.annotation.component.BambooComponent;
import java.util.HashMap;

@BambooComponent
public class AgentsThrottled {

    private final DateTime dateTime;
    // build key maps to start time that agent was throttled
    private final HashMap<String, Long> agentsThrottled;

    public AgentsThrottled(DateTime dateTime) {
        this.dateTime = dateTime;
        agentsThrottled = new HashMap<>();
    }

    /**
     * Add the build key of the event being throttled to keep track of how many agents are being throttled.
     *
     * @param key Build key of the event being throttled
     */
    public void add(String key) {
        if (!agentsThrottled.containsKey(key)) {
            agentsThrottled.put(key, dateTime.getCurrentTime());
        }
    }

    /**
     * Remove the build key from the throttled events if the agent is about to be created.
     *
     * @param key Build key of the event being throttled
     */
    public void remove(String key) {
        agentsThrottled.remove(key);
    }

    /**
     * Get the total amount of agents which are currently being throttled.
     *
     * @return the number of agents currently being throttled.
     */
    public long getTotalAgentsThrottled() {
        return agentsThrottled.size();
    }

    /**
     * Get the number of agents which have been throttled for at least a specified number of minutes.
     *
     * @param minutes minimum number of minutes agents have been throttled
     * @return number of agents throttled for longer than the specified number of minutes
     */
    public long numAgentsThrottledLongerThanMinutes(int minutes) {
        return agentsThrottled.values().stream()
                .filter(startTimeThrottled -> throttledOverXMinutesAgo(startTimeThrottled, minutes))
                .count();
    }

    private boolean throttledOverXMinutesAgo(long timeThrottled, int minutesAgo) {
        return dateTime.getCurrentTime() > (timeThrottled + ((long) minutesAgo * 60 * 1000));
    }
}
