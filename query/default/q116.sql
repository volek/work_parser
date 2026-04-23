-- q116: advanced complexity query 116
WITH base AS (
  SELECT m.id, m.process_name, m.state, m.__time
  FROM default_process_default m
  WHERE m.__time >= CURRENT_TIMESTAMP - INTERVAL '14' DAY
), arr AS (
  SELECT process_id, var_category, var_path, var_value, var_type
  FROM default_process_variables_array_indexed
  WHERE var_path LIKE 'operations[%].steps[%].attempts[%].result'
)
SELECT b.process_name, b.state, COUNT(*) AS cnt, COUNT(DISTINCT b.id) AS processes
FROM base b
LEFT JOIN arr a ON a.process_id = b.id
GROUP BY 1,2
ORDER BY cnt DESC, processes DESC
LIMIT 200;
