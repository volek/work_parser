SELECT "process.processName", "process.id", "node.nodeName", "node.error",  millis_to_timestamp("node.triggerTime") as "exceptionTime", "process.variables.epkId"
FROM $projectName
WHERE "node.nodeType" = 'startEvent'  and REGEXP_LIKE("node.nodeName", 'ception')
and millis_to_timestamp("node.triggerTime") >= CURRENT_TIMESTAMP - INTERVAL '10' MINUTE
and "__time" >= CURRENT_TIMESTAMP - INTERVAL '10' MINUTE