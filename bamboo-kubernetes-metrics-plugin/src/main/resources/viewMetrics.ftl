[#-- @ftlvariable name="action" type="com.atlassian.bamboo.ww2.actions.chains.ArtifactUrlRedirectAction" --]
[#-- @ftlvariable name="" type="com.atlassian.bamboo.ww2.actions.chains.ArtifactUrlRedirectAction" --]

<head xmlns="http://www.w3.org/1999/html">
    <meta name="decorator" content="atl.result">
    <title>PBC Container Metrics</title>
    <meta name="tab" content="PBC Metrics"/>
</head>

<body>
<script src="${req.contextPath}/download/resources/com.atlassian.buildeng.bamboo-kubernetes-metrics-plugin:kubernetes-metrics-resources/d3_v3.js"></script>
<script src="${req.contextPath}/download/resources/com.atlassian.buildeng.bamboo-kubernetes-metrics-plugin:kubernetes-metrics-resources/rickshaw.js"></script>
<link type="text/css" rel="stylesheet" href="${req.contextPath}/download/resources/com.atlassian.buildeng.bamboo-kubernetes-metrics-plugin:kubernetes-metrics-resources/rickshaw.css">
<link type="text/css" rel="stylesheet" href="${req.contextPath}/download/resources/com.atlassian.buildeng.bamboo-kubernetes-metrics-plugin:kubernetes-metrics-resources/graph.css">
<h1>PBC Container Metrics</h1>
Shows CPU and memory unitization of PBC containers used in the build. If absent, the metrics were likely not generated or data is missing. Look for an error at the very end of the build log: "Failed to execute plugin 'Retreive Container Metrics from Prometheus' with error: ...".
[#list containerList as container]
<h2>${container.name} container</h2>
<h3>Memory usage</h3>
<div class="chartContainer">
    <div class="yAxis" id="${container.name}-limit-line-memory"></div>
    <div class="yAxis" id="${container.name}-y-axis-memory"></div>
    <div class="chart" id="${container.name}-memory-chart"></div>
</div>
<h3>CPU usage</h3>
<div class="chartContainer">
    <div class="yAxis" id="${container.name}-y-axis-cpu"></div>
    <div class="chart" id="${container.name}-cpu-chart"></div>
</div>
[/#list]

<script type="text/javascript">
var tickFormat = function(y) {
    var abs_y = Math.abs(y);
    if (abs_y >= 1000000000) { return y / 1000000000 + "GB" }
    else if (abs_y >= 1000000)    { return y / 1000000 + "MB" }
    else if (abs_y >= 1000)       { return y / 1000 + "KB" }
    else if (abs_y < 1 && abs_y > 0)  { return y.toFixed(2) }
    else if (abs_y === 0)         { return '' }
    else                      { return y }
};

[#list containerList as container]
var memoryGraph = new Rickshaw.Graph( {
    element: document.querySelector("#${container.name}-memory-chart"),
    renderer: 'line',
    series: [{"color": "steelblue", "name": "memory", "data": ${container.memoryMetrics}}],
});
var cpuGraph = new Rickshaw.Graph( {
    element: document.querySelector("#${container.name}-cpu-chart"),
    renderer: 'line',
    series: [{"color": "steelblue", "name": "cpu", "data": ${container.cpuMetrics}}],
});

var xAxisMemory = new Rickshaw.Graph.Axis.Time( { graph: memoryGraph } );
var xAxisCpu = new Rickshaw.Graph.Axis.Time( { graph: cpuGraph } );

var yAxisMemory = new Rickshaw.Graph.Axis.Y( {
    graph: memoryGraph,
    orientation: 'left',
    tickFormat: tickFormat,
    element: document.getElementById('${container.name}-y-axis-memory'),
} );
var yAxisCpu = new Rickshaw.Graph.Axis.Y( {
    graph: cpuGraph,
    orientation: 'left',
    tickFormat: Rickshaw.Fixtures.Number.formatKMBT,
    element: document.getElementById('${container.name}-y-axis-cpu'),
} );

var hoverDetailMemory = new Rickshaw.Graph.HoverDetail( {
    graph: memoryGraph,
    yFormatter: function(y) { return (y/1000000).toFixed(2) + " MB" }
} );
var hoverDetailCpu = new Rickshaw.Graph.HoverDetail( {
    graph: cpuGraph,
    yFormatter: function(y) { return y.toFixed(2) + " cores" }
} );

var limitLineMemory = new Rickshaw.Graph.Axis.Y( {
    graph: memoryGraph,
    orientation: 'left',
    tickValues: [${container.memoryLimit} * 1000000],
    tickFormat: tickFormat,
    element: document.getElementById('${container.name}-limit-line-memory'),
} );


memoryGraph.render();
cpuGraph.render();

d3.select("g[data-y-value=\"" + tickFormat(${container.memoryLimit} * 1000000) + "\"]")
        .style("stroke", "rgba(255,0,0,1)")
        .style("stroke-dasharray", "0");

[/#list]

</script>
</body>