SELECT ROUND(cast(t2."count2" as float)/cast(t1."count1" as float)*100, 2) as "% Закрытых" FROM
(SELECT DISTINCT "processName", SUM(1) as "count1" FROM
    (
        SELECT DISTINCT "process.processName" as processName, "process.id" as "process.id"
        FROM "$projectName"
        WHERE "process.startDate" > TIMESTAMP_TO_MILLIS($from) and
            "process.startDate" < TIMESTAMP_TO_MILLIS($to) and
            "process.projectId"='$sberflow_id' AND
            "process.processId"='$process.processId' AND
            "node.nodeDefinitionId" IN ('Event_1wkwvkt','Event_1xf5z0f','Event_0munacb')
    )
    GROUP BY "processName"
) as t1
LEFT JOIN
(SELECT DISTINCT "processName", count (*) as "count2" FROM
    (
        SELECT DISTINCT "process.id" as "process_id", "process.processName" as processName
        FROM "$projectName"
        WHERE "process.processId"='$process.processId' and
        "node.nodeDefinitionId" IN ('Event_0munacb') and
        "process.startDate" > TIMESTAMP_TO_MILLIS($from) and
        "process.startDate" < TIMESTAMP_TO_MILLIS($to) and
        "process.projectId"='$sberflow_id'
    )
    GROUP BY "processName"
) as t2
ON t1."processName" = t2."processName"