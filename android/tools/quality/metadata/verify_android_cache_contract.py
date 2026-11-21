#!/usr/bin/env python3
"""Exercise the *actual SQL literals* shipped in Android SQLiteOpenHelper on portable sqlite3.

This is NOT a substitute for Android SQLiteOpenHelper migration/device testing.
"""
from pathlib import Path
import re, sqlite3, tempfile
from contextlib import contextmanager
ROOT=Path(__file__).resolve().parents[3]
SRC=ROOT/'app/src/main/java/com/auras/orbit/metadata/store'

@contextmanager
def db_connection(path):
    """Commit/rollback like sqlite3's context manager, then close for Windows cleanup."""
    connection=sqlite3.connect(path)
    try:
        with connection:
            yield connection
    finally:
        connection.close()

def actual_create_statements(path):
    text=path.read_text()
    create=text.split('@Override public void onCreate(SQLiteDatabase db) {',1)[1].split('\n    }',1)[0]
    statements=[]
    for match in re.finditer(r'db\.execSQL\((.*?)\);',create,re.S):
        chunks=re.findall(r'"((?:\\.|[^"\\])*)"',match.group(1))
        assert chunks, f'No SQL string in {path.name}'
        statements.append(''.join(c.replace('\\"','"') for c in chunks))
    assert statements, f'No onCreate schema in {path.name}'
    return statements

cache=actual_create_statements(SRC/'OrbitMetadataCacheDatabase.java')
user=actual_create_statements(SRC/'OrbitMetadataUserDecisions.java')
# Include additive table factories invoked by onCreate and by legacy v1->v2->v3 upgrades.
user_text=(SRC/'OrbitMetadataUserDecisions.java').read_text()
def factory_sql(method):
    body=user_text.split(f'private static void {method}(SQLiteDatabase db) {{',1)[1].split('\n    }',1)[0]
    statements=[]
    for match in re.finditer(r'db\.execSQL\((.*?)\);',body,re.S):
        chunks=re.findall(r'"((?:\\.|[^"\\])*)"',match.group(1))
        assert chunks, f'Missing SQL in {method}'
        statements.append(''.join(chunks))
    return statements
identity_sql=factory_sql('createCatalogLinkTable')
history_sql=factory_sql('createHistoryTable')
assert len(identity_sql)==1 and len(history_sql)==2
user += identity_sql + history_sql
assert 'public static final int VERSION = 3' in user_text
assert all('TOKEN' not in sql and 'COOKIE' not in sql for sql in cache)
assert all('action' not in sql.lower() and 'episode_data' not in sql.lower() for sql in cache)
assert 'orbit_metadata_cache.db' in (SRC/'OrbitMetadataCacheDatabase.java').read_text()
assert 'orbit_metadata_user_decisions.db' in (SRC/'OrbitMetadataUserDecisions.java').read_text()
assert 'orbit_local_library.db' not in ''.join(cache+user)
with tempfile.TemporaryDirectory() as temp:
    cpath=Path(temp)/'orbit_metadata_cache.db'; upath=Path(temp)/'orbit_metadata_user_decisions.db'
    with db_connection(cpath) as con:
        con.execute('PRAGMA foreign_keys=ON')
        for sql in cache: con.execute(sql)
        con.execute('''INSERT INTO source_display VALUES
           (?,?,?,?,?,?,?,?,?,?,?,?,?)''',('a'*64,'b'*64,'c'*64,'SERIES','A series','A synopsis','2026','',100,101,102,103,500))
        assert con.execute('SELECT title FROM source_display').fetchone()[0]=='A series'
        for invalid in [
            ('too short','b'*64,'c'*64,'SERIES','A series','A synopsis','2026','',100,101,102,103,500),
            ('a'*64,'b'*64,'c'*64,'WRONG','A series','A synopsis','2026','',100,101,102,103,500),
        ]:
            try:con.execute('INSERT OR REPLACE INTO source_display VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',invalid)
            except sqlite3.IntegrityError:pass
            else: raise AssertionError('Cache admitted invalid scope/kind')
    with db_connection(upath) as con:
        for sql in user: con.execute(sql)
        con.execute('''INSERT INTO user_display_override VALUES (?,?,?,?,?,?)''',
            ('b'*64,'a'*64,'SYNOPSIS','User correction',1,200))
        con.execute('''INSERT INTO user_catalog_link VALUES (?,?,?,?,?,?,?,?)''',
            ('b'*64,'a'*64,'TMDB','SERIES',445,'LINK',1,201))
        con.execute('INSERT INTO user_decision_history '
            '(profile_scope,source_key,field,previous_value,next_value,operation,modified_ms) '
            'VALUES (?,?,?,?,?,?,?)',('b'*64,'a'*64,'SYNOPSIS','','User correction','SET',201))
        con.commit()
        assert con.execute('SELECT count(*) FROM user_decision_history').fetchone()[0]==1
        try:
            con.execute('INSERT INTO user_decision_history '
                '(profile_scope,source_key,field,previous_value,next_value,operation,modified_ms) '
                'VALUES (?,?,?,?,?,?,?)',('b'*64,'a'*64,'SOURCE_ACTION','','opaque provider', 'SET',202))
        except sqlite3.IntegrityError: pass
        else: raise AssertionError('History admitted provider executable data field')
        try:
            con.execute('''INSERT INTO user_catalog_link VALUES (?,?,?,?,?,?,?,?)''',
                ('b'*64,'c'*64,'TMDB','MOVIE',0,'LINK',1,201))
        except sqlite3.IntegrityError: pass
        else: raise AssertionError('Invalid TMDB ID accepted')
    # A metadata-only reset must not delete durable user decisions.
    with db_connection(cpath) as con:
        con.execute('DELETE FROM source_display'); con.commit()
    with db_connection(upath) as con:
        assert con.execute('SELECT display_value FROM user_display_override').fetchone()[0]=='User correction'
        try:
            con.execute('INSERT INTO user_display_override VALUES (?,?,?,?,?,?)',
                ('b'*64,'a'*64,'SOURCE_ACTION','play token',1,201))
        except sqlite3.IntegrityError:pass
        else:raise AssertionError('Source actions may not be persisted in user corrections')
    # Durable records remain available after cache corruption/replacement.
    cpath.write_bytes(b'corrupt-optional-cache')
    cpath.unlink()
    with db_connection(cpath) as con:
        for sql in cache:con.execute(sql)
        assert con.execute('SELECT count(*) FROM source_display').fetchone()[0]==0
    with db_connection(upath) as con:
        assert con.execute('SELECT count(*) FROM user_display_override').fetchone()[0]==1
        assert con.execute('SELECT catalog_id FROM user_catalog_link').fetchone()[0]==445
    # v1 -> v3 and v2 -> v3 are additive. No destructive migration of user decisions.
    with db_connection(upath) as con:
        con.execute('DROP TABLE IF EXISTS user_decision_history')
        con.execute('DROP INDEX IF EXISTS decision_history_by_source')
        con.execute('DROP TABLE IF EXISTS user_catalog_link')
        assert con.execute('SELECT display_value FROM user_display_override').fetchone()[0]=='User correction'
        for sql in identity_sql + history_sql: con.execute(sql)
        assert con.execute('SELECT display_value FROM user_display_override').fetchone()[0]=='User correction'
        assert con.execute('SELECT count(*) FROM user_decision_history').fetchone()[0]==0
        con.execute('INSERT INTO user_decision_history '
            '(profile_scope,source_key,field,previous_value,next_value,operation,modified_ms) '
            'VALUES (?,?,?,?,?,?,?)',('b'*64,'a'*64,'SYNOPSIS','User correction','', 'CLEAR',203))
        con.commit()
    with db_connection(upath) as con:
        assert con.execute('SELECT operation FROM user_decision_history').fetchone()[0]=='CLEAR'
        assert con.execute('SELECT display_value FROM user_display_override').fetchone()[0]=='User correction'
        # A v2 -> v3 migration leaves existing catalog links intact.
        con.execute('INSERT INTO user_catalog_link VALUES (?,?,?,?,?,?,?,?)',
            ('b'*64,'a'*64,'TMDB','MOVIE',123,'LINK',1,400))
        con.execute('DROP TABLE IF EXISTS user_decision_history')
        con.execute('DROP INDEX IF EXISTS decision_history_by_source')
        for sql in history_sql: con.execute(sql)
        assert con.execute('SELECT catalog_id FROM user_catalog_link WHERE media_kind="MOVIE"').fetchone()[0]==123
        assert con.execute('SELECT display_value FROM user_display_override').fetchone()[0]=='User correction'
    # Check the shipped Java mutation methods record edits and removals atomically.
    assert 'undoLastDisplayChange' in user_text
    assert 'appendHistory(db, profileScope, sourceKey' in user_text
    assert 'appendHistory(db, scope, source, field.name(), previous, "", "CLEAR"' in user_text
    assert 'if (!current.equals(after)) return false;' in user_text
    activity=(ROOT/'app/src/main/java/com/auras/orbit/ui/OrbitShellActivity.java').read_text()
    assert 'orbit_route_focus_' in activity and 'Undo last title edit' in activity
    method=activity.split('protected void onSaveInstanceState(Bundle outState) {',1)[1].split('public void onBackPressed()',1)[0]
    assert method.index('if (archiveRecoveryScreenActive || shellState == null)') < method.index('for (ShellRoute savedRoute : ShellRoute.values())')
    assert 'getCurrentFocus()' in method
    assert 'rememberVisibleTopLevelScroll();' in method
    assert 'restoreTopLevelScrollIfRequested(scroll);' in activity
    application=(ROOT/'app/src/main/java/com/auras/orbit/OrbitApplication.java').read_text()
    read_method=application.split('public void readSafeMetadataDisplay(',1)[1].split('public void updateUserDisplayCorrection(',1)[0]
    assert read_method.count('try {') >= 3
    assert read_method.index('cached = metadataCacheDatabase.readProviderDisplay') < read_method.index('title = metadataUserDecisions.read')
    assert 'metadataCacheDatabase.recoverCorruptCache(this);' in application
    cache_store=(SRC/'OrbitMetadataCacheDatabase.java').read_text()
    recovery=cache_store.split('public synchronized boolean recoverCorruptCache(',1)[1].split('\n    }',1)[0]
    assert 'context.deleteDatabase(DATABASE_NAME);' in recovery
    assert 'OrbitMetadataUserDecisions' not in recovery and 'deleteDatabase(DATABASE_NAME)' in recovery
print('ANDROID_METADATA_SQL_CONTRACT_PASS shipped_create_ddl=%d user_ddl=%d migration_shapes=2' %(len(cache),len(user)))
