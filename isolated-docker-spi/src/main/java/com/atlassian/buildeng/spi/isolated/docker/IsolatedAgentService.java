package com.atlassian.buildeng.spi.isolated.docker;

import java.util.List;

public interface IsolatedAgentService {
    /**
     * Start an isolated docker agent to handle the build request
     *
     * @param request - request object
     * @return Any implementation specific errors that prevent agent startup
     * @throws IsolatedDockerAgentException Any bamboo related errors that prevent agent startup
     */
    IsolatedDockerAgentResult startAgent(IsolatedDockerAgentRequest request) throws IsolatedDockerAgentException;

    List<String> getKnownDockerImages();
}
