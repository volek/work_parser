-- q105: advanced complexity query 105
SELECT
  m.id,
  m.process_name,
  m.state,
  ph.var_value AS phone,
  nm.var_value AS surname,
  js.value_json AS name_obj
FROM default_process_default m
LEFT JOIN default_process_variables_array_indexed ph ON ph.process_id = m.id AND ph.var_path LIKE 'epkData.epkEntity.phoneNumbers[%].phoneNumber'
LEFT JOIN default_process_variables_array_indexed nm ON nm.process_id = m.id AND nm.var_path LIKE 'epkData.epkEntity.names[%].surname'
LEFT JOIN default_process_variables_array_indexed js ON js.process_id = m.id AND js.var_path LIKE 'epkData.epkEntity.names[%]' AND js.var_type='json'
WHERE m.__time >= CURRENT_TIMESTAMP - INTERVAL '10' DAY
ORDER BY m.__time DESC
LIMIT 400;
