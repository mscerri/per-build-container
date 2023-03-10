
${webResourceManager.requireResourcesForContext("docker.jobConfiguration")}

[#if custom.isolated.docker.templateAccessible]
    [@ui.bambooSection titleKey="isolated.docker.misc.header" descriptionKey='isolated.docker.misc.header.description']
        [@ww.textfield cssClass='long-field docker-container-autocomplete' required=true
        labelKey='isolated.docker.image' name='custom.isolated.docker.image' descriptionKey="isolated.docker.image.description"
        /]

    [#--  Only show the CPU architecture section if the server has a non-empty config or a job has the property already configured  --]
        [#if architectureConfig?size gt 0]
            [@ww.select labelKey='isolated.docker.architecture' name='custom.isolated.docker.architecture' descriptionKey="isolated.docker.architecture.description"
            list=architectureConfig listKey='first' listValue='second' cssClass="long-field" ]
            [/@ww.select]
        [/#if]

        [@ww.select labelKey='isolated.docker.size' name='custom.isolated.docker.imageSize'
        list=imageSizes listKey='first' listValue='second' cssClass="long-field" ]
        [/@ww.select]
        [@ww.hidden cssClass='long-field docker-extra-containers' name='custom.isolated.docker.extraContainers' /]
        [#include "extraContainersUI.ftl"]

        [@ww.textfield cssClass='long-field' required=false
        labelKey='isolated.docker.role' name='custom.isolated.docker.role' descriptionKey="isolated.docker.role.description"
        /]

    [/@ui.bambooSection]

    [#include "extraContainersDialog.ftl"]
[#elseif fn.hasAdminPermission()]
    [@ui.messageBox type="warning"]
        <p>
            [@s.text name="isolated.docker.templateAccessible.warning" /]
            <a href="${req.contextPath}/admin/viewIsolatedDockerConfiguration.action">
                [@s.text name="isolated.docker.templateAccessible.admin.info" /]
            </a>
        </p>
    [/@ui.messageBox]
[#else]
    [@ui.messageBox type="warning"]
        <p>
            [@s.text name="isolated.docker.templateAccessible.warning" /]
            <a href="${req.contextPath}/viewAdministrators.action">Find an admin</a> who can configure the plugin for you.
        </p>
    [/@ui.messageBox]
[/#if]

<script>
    [#include "jobConfiguration.js"]
</script>
