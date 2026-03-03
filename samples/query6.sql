SELECT "Статус", "Кол-во"
FROM(SELECT "node.nodeName" as "Статус", count(DISTINCT "node.id") as "Кол-во"
    FROM "$projectName"
    WHERE "__time" > $from and "__time" < $to AND "process.processId"='$processName'
    and "process.state"=2 AND "node.nodeType"='endEvent'
    and "node.leaveTime" is not null
    and "node.error"='none'
    GROUP BY "node.nodeName"
 )