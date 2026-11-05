function(n, o, user) {
  function reject(message) { throw({forbidden: message}); }
  function text(s) { return typeof s === 'string' && s.length > 0; }
  function positive(n) { return typeof n === 'number' && n > 0 && n <= 2147483647 && n === Math.floor(n); }
  function uuid(v) { return typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v); }
  function instant(v) {
    if (typeof v !== 'string') return false;
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/.exec(v);
    if (!m) return false;
    var d = new Date(v);
    return !isNaN(d.getTime()) && d.getUTCFullYear() === +m[1] && d.getUTCMonth()+1 === +m[2]
      && d.getUTCDate() === +m[3] && d.getUTCHours() === +m[4] && d.getUTCMinutes() === +m[5] && d.getUTCSeconds() === +m[6];
  }
  if (n._id.indexOf('_design/') === 0) return;
  if (n._deleted) reject('Use logical deletion to preserve history');
  if (['event', 'order', 'settings'].indexOf(n.kind) < 0) reject('Unknown kind');
  if (n.schemaVersion !== 2) reject('schemaVersion must equal 2');
  if (!instant(n.updatedAt) || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{9}Z$/.test(n.updatedAt)) reject('Canonical UTC updatedAt required');
  if (!n.lastModified || !text(n.lastModified.by) || !text(n.lastModified.action) || n.lastModified.at !== n.updatedAt || n.lastModified.state !== n.state) reject('Invalid lastModified');
  if (!Array.isArray(n.history) || !n.history.length) reject('History required');
  if (JSON.stringify(n.history[n.history.length-1]) !== JSON.stringify(n.lastModified)) reject('History tail must equal lastModified');
  if (['OPEN','CLOSED','CANCELLED','DELETED'].indexOf(n.state) < 0) reject('Invalid state');
  if (o) {
    if (n.kind !== o.kind || n.id !== o.id || n.createdAt !== o.createdAt) reject('Identity is immutable');
    if (o.schemaVersion === 2) {
      if (n.history.length <= o.history.length) reject('Append history entries');
      for (var i=0; i<o.history.length; i++) {
        if (JSON.stringify(o.history[i]) !== JSON.stringify(n.history[i])) reject('History is append-only');
      }
      if (n.updatedAt < o.updatedAt) reject('updatedAt must not move backwards');
    }
  }
  if (n.kind === 'event') {
    if (!uuid(n.id) || !instant(n.createdAt) || n._id !== 'event:'+n.id || !text(n.title) || !text(n.type) || !instant(n.startsAt) || !positive(n.capacity)) reject('Invalid event');
  }
  if (n.kind === 'order') {
    if (!uuid(n.id) || !uuid(n.draftId) || !instant(n.createdAt) || !text(n.status) || n._id !== 'order:'+n.id || !uuid(n.eventId) || !text(n.managerId) || !positive(n.quantity)) reject('Invalid order');
    if (o && (n.eventId !== o.eventId || n.draftId !== o.draftId)) reject('Order references are immutable');
  }
  if (n.kind === 'settings' && (!text(n.managerId) || n._id !== 'settings:'+n.managerId || !positive(n.defaultQuantity))) reject('Invalid settings');
}
