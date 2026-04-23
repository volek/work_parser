-- q077: join main+arrays path limits[0].rules[0].thresholds[0].value
SELECT
  m.id,
  m.process_name,
  m.state,
  a.var_category,
  a.var_path,
  a.var_type,
  a.var_value
FROM default_process_default m
JOIN default_process_variables_array_indexed a
  ON a.process_id = m.id
WHERE a.var_path = 'limits[0].rules[0].thresholds[0].value'
  AND m.__time >= CURRENT_TIMESTAMP - INTERVAL '12' DAY
ORDER BY m.__time DESC, a.var_path
LIMIT 177;
