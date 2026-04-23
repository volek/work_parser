-- q113: advanced complexity query 113
WITH ranked AS (
  SELECT
    m.id,
    m.process_name,
    a.var_path,
    a.var_value,
    m.__time,
    ROW_NUMBER() OVER (PARTITION BY m.id, a.var_path ORDER BY m.__time DESC) AS rn
  FROM default_process_default m
  JOIN default_process_variables_array_indexed a ON a.process_id = m.id
)
SELECT *
FROM ranked
WHERE rn = 1
ORDER BY __time DESC
LIMIT 500;
