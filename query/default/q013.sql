-- q013: main-only pattern 13
SELECT
  m.id,
  m.process_id,
  m.process_name,
  m.state,
  m.__time
FROM default_process_default m
WHERE m.__time >= CURRENT_TIMESTAMP - INTERVAL '14' HOUR
  AND m.state IN (0,1,2)
ORDER BY m.__time DESC
LIMIT 63;
