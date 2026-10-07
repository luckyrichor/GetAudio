import pytest
from fastapi import HTTPException
from getaudio import credentials
from getaudio.db import init_db
from getaudio.config import settings


def test_ticket_moves_once_and_reencrypts():
    from getaudio.credential_migration import issue_ticket, claim
    init_db()
    credentials.save('guest_migration', 'douyin', {'sessionid': 'fixture'})
    ticket = issue_ticket('guest_migration')
    assert claim(ticket, 'acct_migration')['platforms'] == ['douyin']
    assert credentials.load('acct_migration', 'douyin') == {'sessionid': 'fixture'}
    assert credentials.load('guest_migration', 'douyin') is None
    assert not (settings.cookie_dir / 'guest_migration__douyin.txt').exists()
    assert claim(ticket, 'acct_migration')['platforms'] == ['douyin']
    with pytest.raises(HTTPException) as e:
        claim(ticket, 'acct_other')
    assert e.value.status_code == 409


def test_ticket_conflict_preserves_both_and_expired_rejected():
    from getaudio.credential_migration import issue_ticket, claim
    init_db()
    credentials.save('guest_conflict', 'bilibili', {'SESSDATA': 'source'})
    credentials.save('acct_conflict', 'bilibili', {'SESSDATA': 'target'})
    ticket = issue_ticket('guest_conflict')
    with pytest.raises(HTTPException):
        claim(ticket, 'acct_conflict')
    assert credentials.load('guest_conflict', 'bilibili')['SESSDATA'] == 'source'
    assert credentials.load('acct_conflict', 'bilibili')['SESSDATA'] == 'target'
    with pytest.raises(HTTPException):
        claim(issue_ticket('guest_conflict', expires_in=-1), 'acct_new')
    with pytest.raises(HTTPException):
        claim('guest_conflict', 'acct_new')
