SELECT "process.id" as "pid",
"process.variables.caseId" as "caseId",
"process.variables.staticData.clientEpkId" as "epkId",
"process.variables.staticData.casePublicId" as "casePublicId",
"process.variables.staticData.registrationTime" as "registrationTime",
"process.variables.answerType" as "answerType"
FROM $projectName
WHERE "process.processName" = 'uvskFraudFin_front'
and "node.nodeName" = 'setCaseStage addCaseClientPayments'
and "__time" >= CURRENT_TIMESTAMP - INTERVAL '24' HOUR
GROUP BY 1,2,3,4,5,6
order by "registrationTime"