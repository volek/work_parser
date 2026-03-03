SELECT "process.processName", "process.id", "node.nodeName", "node.error",  millis_to_timestamp("node.triggerTime") as "exceptionTime", "process.variables.epkId", "process.rootProcessId", "process.processId"
FROM $projectName
WHERE  "process.processName" = 'tappeal_p2p_receiver'
and "node.nodeType" = 'subProcess'
and "node.nodeName" like '%GCT%'
and millis_to_timestamp("node.triggerTime") >= CURRENT_TIMESTAMP - INTERVAL '15' MINUTE
and "__time" >= CURRENT_TIMESTAMP - INTERVAL '15' MINUTE