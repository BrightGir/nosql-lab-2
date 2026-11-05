import copy, unittest
from couch_admin import upgrade
class MigrationTest(unittest.TestCase):
    def test_repeat_preserves_revision_and_payload(self):
        doc={'_id':'event:1','_rev':'1-test','kind':'event','title':'Books','createdAt':'2026-01-01T00:00:00Z'}
        self.assertTrue(upgrade(doc)); migrated=copy.deepcopy(doc)
        self.assertFalse(upgrade(doc)); self.assertEqual(doc,migrated)
        self.assertEqual(doc['_rev'],'1-test');self.assertEqual(doc['title'],'Books')
        self.assertEqual(doc['schemaVersion'],2)
        self.assertEqual(doc['history'][-1],doc['lastModified'])
    def test_timezone_is_normalized(self):
        doc={'createdAt':'2026-01-01T03:00:00+03:00'};upgrade(doc)
        self.assertEqual(doc['updatedAt'],'2026-01-01T00:00:00.000000000Z')
if __name__=='__main__':unittest.main()
