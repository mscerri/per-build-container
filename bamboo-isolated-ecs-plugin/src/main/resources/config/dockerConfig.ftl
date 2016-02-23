<head xmlns="http://www.w3.org/1999/html">
    <meta name="decorator" content="atl.admin">
    <title>Configure Isolated Docker</title>
    ${webResourceManager.requireResourcesForContext("viewDockerConfiguration")}
</head>

<body>
<h1>Configure Isolated Docker</h1><br>

<h2>Registered Docker Images</h2><br>

<table id="dockerImageTable" class="aui">
    <tr>
        <th>Docker Image</th>
        <th></th>
    </tr>
</table>

<br>

<h2>Register Docker Images</h2>

<form id="registerDockerImage" class="aui">
    <fieldset>
        <legend><span>Register New Image</span></legend>
        <div class="field-group">
            <label for="dockerImageToRegister">Docker Repository</label>
            <input type="text" class="text long-field" id="dockerImageToRegister"
                      placeholder="e.g. docker.atlassian.io/buildeng-agent:baseagent:latest"></input>
            <button type="button" class="aui-button" onclick="registerImage()">Register</button>
        </div>
    </fieldset>
</form>

<br>

<h2>Set Bamboo Sidekick</h2>

<form id="setBambooSidekick" class="aui">
    <fieldset>

        <legend><span>Set Sidekick</span></legend>
        <div class="field-group">
            <label for="sidekickToUse">Sidekick Repository</label>
            <input type="text" class="text long-field" id="sidekickToUse"
                    placeholder=""></input>
            <button type="button" class="aui-button" onclick="setSidekick()">Set</button>
            <button type="button" class="aui-button" onclick="resetSidekick()">Reset</button>
        </div>
    </fieldset>
</form>

<br>

<h2>Set ECS Cluster</h2><br>

<!-- Trigger -->
<a href="#clusters" aria-owns="clusters" aria-haspopup="true" id="currentCluster"
   class="aui-button aui-style-default aui-dropdown2-trigger"></a>

<!-- Dropdown -->
<div id="clusters" class="aui-style-default aui-dropdown2">
    <ul class="aui-list-truncate" id="clusterList">
    </ul>
</div>
</body>