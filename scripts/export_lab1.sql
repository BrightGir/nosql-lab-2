-- Run with psql -tA: emits a single JSON object accepted by couch_admin.py import.
SELECT json_build_object(
  'events', COALESCE((SELECT json_agg(json_build_object(
    'id', id, 'title', title, 'type', type, 'startsAt', starts_at,
    'capacity', capacity, 'createdAt', created_at)) FROM events), '[]'::json),
  'orders', COALESCE((SELECT json_agg(json_build_object(
    'id', id, 'draftId', draft_id, 'eventId', event_id, 'managerId', manager_id,
    'quantity', quantity, 'status', status, 'createdAt', created_at)) FROM orders), '[]'::json),
  'settings', COALESCE((SELECT json_agg(json_build_object(
    'managerId', manager_id, 'defaultQuantity', default_quantity)) FROM manager_settings), '[]'::json)
);
