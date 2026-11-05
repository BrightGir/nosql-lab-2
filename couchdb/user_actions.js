function(doc) {
  if (doc.history && doc.kind) {
    doc.history.forEach(function(entry) {
      if (entry.by && entry.action) emit([entry.by, entry.action], 1);
    });
  }
}
