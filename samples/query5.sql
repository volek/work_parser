SELECT *
FROM (
         SELECT
             ROOT_VARS."process.id",
           --  TIME_FORMAT(MILLIS_TO_TIMESTAMP("process.startDate"),'dd.MM.yy ','+03:00') AS "Дата создания процесса",
           --  TIME_FORMAT(MILLIS_TO_TIMESTAMP("process.startDate"),'HH:mm:ss','+03:00') AS "Время создания процесса",
            "process.startDate" AS "Дата создания процесса",
            "process.startDate" AS "Время создания процесса",
             ROOT_VARS."process.processName",
             ROOT_VARS."process.variables.processKeys.bbmoId"
         FROM (
                  SELECT
                      "process.id",
                      "process.startDate",
                      "process.processName",
                      "process.variables.processKeys.bbmoId"
                  FROM $projectName
                      where "__time" > $from and "__time" < $to
                      AND "process.processId" = 'Process_1bf0fc2'
                  GROUP BY
                      "process.id",
                      "process.startDate",
                      "process.processName",
                      "process.variables.processKeys.bbmoId"
              ) ROOT_VARS INNER JOIN (
             SELECT
                 "process.id"
             FROM $projectName
                 where "__time" > $from and "__time" < $to
                 AND "process.processId" = 'Process_1bf0fc2'
             GROUP BY
                 "process.id"
         ) ROOT_DUB_FILTER ON ROOT_DUB_FILTER."process.id" = ROOT_VARS."process.id"
     ) ROOT
         JOIN (
    SELECT
        STATUS_VARS."process.rootInstanceId",
        STATUS_VARS."process.variables.status",
        STATUS_VARS."startDate"
    FROM (
             SELECT
                 "process.rootInstanceId",
                 "process.variables.status",
                 MAX("process.startDate") AS "startDate"
             FROM $projectName
                 where "__time" > $from and "__time" < $to
                 AND "process.processId" = 'Process_1d439b7' AND "process.variables.status" NOT IN ('Resolved-Completed','Resolved-Rejected','Pending-IRD')
             GROUP BY
                 "process.rootInstanceId",
                 "process.variables.status"
         ) STATUS_VARS INNER JOIN (
        SELECT
            "process.rootInstanceId",
            MAX("process.startDate") AS "startDate"
        FROM $projectName
            where "__time" > $from and "__time" < $to
            AND "process.processId" = 'Process_1d439b7'
        GROUP BY
            "process.rootInstanceId"
    ) STATUS_DUB_FILTER ON STATUS_VARS."process.rootInstanceId" = STATUS_DUB_FILTER."process.rootInstanceId" AND STATUS_VARS."startDate" = STATUS_DUB_FILTER."startDate"
) STATUS ON ROOT."process.id" = STATUS."process.rootInstanceId"
         JOIN (
    SELECT
        WAIT_VARS."process.rootInstanceId",
        WAIT_VARS."process.id",
        WAIT_VARS."startDate"
    FROM (
             SELECT
                 "process.rootInstanceId",
                 "process.id",
                 MAX("process.startDate") AS "startDate"
             FROM $projectName
                 where "__time" > $from and "__time" < $to
                 AND "process.processId" = 'Process_c969d89'
             GROUP BY
                 "process.rootInstanceId",
                 "process.id"
         ) WAIT_VARS INNER JOIN (
        SELECT
            "process.rootInstanceId",
            MAX("process.startDate") AS "startDate"
        FROM $projectName
            where "__time" > $from and "__time" < $to
            AND "process.processId" = 'Process_c969d89'
        GROUP BY
            "process.rootInstanceId"
    ) WAIT_DUB_FILTER ON WAIT_VARS."process.rootInstanceId" = WAIT_DUB_FILTER."process.rootInstanceId" AND WAIT_VARS."startDate" = WAIT_DUB_FILTER."startDate"
) IDP ON ROOT."process.id" = IDP."process.rootInstanceId"