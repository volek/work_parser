-- q102: advanced complexity query 102
SELECT
  m.process_name,
  a.var_category,
  a.var_path,
  COUNT(*) AS rows_cnt,
  COUNT(DISTINCT m.id) AS process_cnt,
  SUM(CASE WHEN a.var_type = 'json' THEN 1 ELSE 0 END) AS json_cnt
FROM default_process_default m
JOIN default_process_variables_array_indexed a ON a.process_id = m.id
WHERE m.__time >= CURRENT_TIMESTAMP - INTERVAL '30' DAY
GROUP BY 1,2,3
HAVING COUNT(*) >= 2
ORDER BY rows_cnt DESC, process_cnt DESC
LIMIT 300;
