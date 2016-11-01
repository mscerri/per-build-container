package com.atlassian.buildeng.ecs.scheduling;

import com.amazonaws.services.ecs.model.ContainerDefinition;
import com.atlassian.buildeng.spi.isolated.docker.Configuration;

public interface Constants {

    /**
     * the storage driver used with docker on the ec2 instances in ecs. For docker-in-docker to work
     * we need to use this one in place of the default vfs that is terribly slow.
     */
    public static String storage_driver = System.getProperty("pbc.dind.storage.driver", "overlay");

    // ECS

    // The name of the sidekick docker image and sidekick container
    String SIDEKICK_CONTAINER_NAME = "bamboo-agent-sidekick";

    // The name of the agent container
    String AGENT_CONTAINER_NAME = "bamboo-agent";

    // The name of the build directory volume for dind
    String BUILD_DIR_VOLUME_NAME = "build-dir";

    /**
     * The environment variable to override on the agent per image
     */
    String ENV_VAR_IMAGE = "IMAGE_ID";

    /**
     * The environment variable to override on the agent per server
     */
    String ENV_VAR_SERVER = "BAMBOO_SERVER";
    
    /**
     * The environment variable to set the result spawning up the agent
     */
    String ENV_VAR_RESULT_ID = "RESULT_ID";

    // The working directory of isolated agents
    String WORK_DIR = "/buildeng";

    // The working directory for builds
    String BUILD_DIR = WORK_DIR + "/bamboo-agent-home/xml-data/build-dir";

    // The script which runs the bamboo agent jar appropriately
    String RUN_SCRIPT = WORK_DIR + "/" + "run-agent.sh";

    // AWS info
    String ECS_CLUSTER_KEY                = "ecs_cluster";
    String ECS_CONTAINER_INSTANCE_ARN_KEY = "ecs-container-arn";

    String RESULT_PART_TASKARN = "TaskARN";

    // Ratio between soft and hard limits
    Double SOFT_TO_HARD_LIMIT_RATIO = 1.25;

    // The container definition of the sidekick
    ContainerDefinition SIDEKICK_DEFINITION =
            new ContainerDefinition()
                    .withName(SIDEKICK_CONTAINER_NAME)
                    .withMemoryReservation(Configuration.DOCKER_MINIMUM_MEMORY)
                    .withEssential(false);

}