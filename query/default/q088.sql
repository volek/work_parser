-- q088: JSON object in arrays path epkData.epkEntity.addresses[0]
SELECT
  m.id,
  m.process_name,
  a.var_path,
  a.var_type,
  a.value_json,
  JSON_VALUE(a.value_json, '$.code') AS code_guess,
  JSON_VALUE(a.value_json, '$.payload.code') AS payload_code_guess
FROM default_process_default m
JOIN default_process_variables_array_indexed a
  ON a.process_id = m.id
WHERE a.var_path = 'epkData.epkEntity.addresses[0]'
  AND a.var_type = 'json'
  AND a.value_json IS NOT NULL
ORDER BY m.__time DESC
LIMIT 168;
