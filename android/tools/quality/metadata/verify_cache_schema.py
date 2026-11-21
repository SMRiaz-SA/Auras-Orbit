#!/usr/bin/env python3
"""Portable SQLite schema-research regression; NOT Android SQLite migration qualification."""
from pathlib import Path
import sqlite3, tempfile, os
ROOT = Path(__file__).resolve().parent
schema = (ROOT / 'metadata_cache_v1.sql').read_text()
checks = 0
def check(condition, why):
    global checks
    checks += 1
    if not condition: raise AssertionError(why)
def reject(conn, sql, params=()):
    global checks
    checks += 1
    try:
        conn.execute(sql, params)
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError('Expected SQLite constraint rejection: ' + sql)
with tempfile.TemporaryDirectory() as td:
    path = os.path.join(td, 'metadata.db')
    con = sqlite3.connect(path)
    con.executescript(schema)
    check(con.execute('PRAGMA foreign_keys').fetchone()[0] == 1, 'FK enforcement')
    check(con.execute('PRAGMA user_version').fetchone()[0] == 1, 'schema version')
    con.execute('INSERT INTO canonical_work VALUES(?,?,?)', ('work_movie','MOVIE',1))
    con.execute('INSERT INTO canonical_work VALUES(?,?,?)', ('work_series','SERIES',1))
    ins='INSERT INTO canonical_external_id VALUES(?,?,?,?,?,?)'
    con.execute(ins, ('TMDB_MOVIE','MOVIE','123','work_movie','exact_tmdb',3))
    con.execute(ins, ('TMDB_TV','SERIES','123','work_series','exact_tmdb',3))
    check(con.execute('SELECT count(*) FROM canonical_external_id WHERE external_id=?',('123',)).fetchone()[0] == 2,
          'same numerical TMDB ID for different media kinds remains distinct')
    reject(con, ins, ('TMDB_MOVIE','MOVIE','123','work_series','wrong',3))
    reject(con, ins, ('TMDB_TV','MOVIE','345','work_movie','wrong',3))
    reject(con, 'UPDATE canonical_work SET kind=? WHERE work_id=?', ('SERIES','work_movie'))
    con.execute('INSERT INTO source_listing VALUES(?,?,?,?,?,?)',
        ('scoped_dgst_movie','profile-digest', 'provider','generation-1','MOVIE',1))
    con.execute('INSERT INTO source_listing VALUES(?,?,?,?,?,?)',
        ('scoped_dgst_series','profile-digest', 'provider','generation-1','SERIES',1))
    con.execute('INSERT INTO identity_decision VALUES(?,?,?,?,?,?,?)',
        ('scoped_dgst_movie','work_movie','ACCEPTED','digest00000000001',1,1,3))
    reject(con, 'INSERT INTO identity_decision VALUES(?,?,?,?,?,?,?)',
        ('scoped_dgst_series','work_movie','ACCEPTED','digest00000000001',1,1,3))
    reject(con, 'UPDATE identity_decision SET work_id=? WHERE source_key=?',
        ('work_series','scoped_dgst_movie'))
    con.execute('INSERT INTO provider_display_snapshot VALUES(?,?,?,?,?,?,?,?,?,?)',
        ('scoped_dgst_series','Series','Overview','2026','',1,2,3,10,512))
    con.executemany('INSERT INTO provider_episode_display VALUES(?,?,?,?,?,?,?)',
        (('scoped_dgst_series', f'episode_key_{i:05d}', 'Subbed',1,i,i,f'Episode {i}')
         for i in range(1,1201)))
    check(con.execute('SELECT count(*) FROM provider_episode_display').fetchone()[0]==1200,
          'all 1,200 display episodes persist')
    reject(con,'INSERT INTO public_field_fact VALUES(?,?,?,?,?,?,?,?,?)',
        ('work_movie','source_action','','TMDB','leak','x',1,1,1))
    reject(con,'INSERT INTO provider_display_snapshot VALUES(?,?,?,?,?,?,?,?,?,?)',
        ('scoped_dgst_movie','Movie','','','',10,4,20,30,512))
    con.commit();con.close()
    con=sqlite3.connect(path)
    con.execute('PRAGMA foreign_keys=ON')
    check(con.execute('SELECT count(*) FROM provider_episode_display').fetchone()[0]==1200,
          'safe metadata survives restart')
    con.execute('DELETE FROM source_listing WHERE source_key=?',('scoped_dgst_series',))
    check(con.execute('SELECT count(*) FROM provider_episode_display').fetchone()[0] == 0,
          'source-specific display records cascade on generation removal')
    check(con.execute('SELECT count(*) FROM canonical_work').fetchone()[0]==2,
          'independent canonical records unaffected')
    con.close()
print('METADATA_CACHE_SCHEMA_RESEARCH_PASS checks=%d (not Android runtime qualification)'%checks)
