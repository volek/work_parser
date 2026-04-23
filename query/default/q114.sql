-- q114: advanced complexity query 114
WITH p AS (
  SELECT id, process_name, state, __time
  FROM default_process_default
  WHERE state IN (0,1,2)
), x AS (
  SELECT process_id, var_path, var_value, value_json
  FROM default_process_variables_array_indexed
  WHERE var_path LIKE 'epkData.epkEntity.%'
)
SELECT
  p.process_name,
  p.state,
  COUNT(*) AS joined_rows,
  COUNT(DISTINCT p.id) AS distinct_processes,
  COUNT(DISTINCT x.var_path) AS distinct_paths
FROM p
LEFT JOIN x ON x.process_id = p.id
GROUP BY 1,2
ORDER BY joined_rows DESC
LIMIT 250;
