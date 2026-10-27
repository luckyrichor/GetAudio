from uuid import uuid4
from fastapi.testclient import TestClient
from getaudio.api import app
from getaudio.accounts import _windows

def register(c):
    _windows.clear()
    phone = '139' + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
    r = c.post('/account/register', json={'phone': phone, 'password': 'test-password-123'})
    assert r.status_code == 200
    data = r.json()
    return data['user_id'], {'X-GetAudio-Session': data['session_token']}


def payload():
    return {'id': str(uuid4()), 'version': '0.8.7', 'occurred_at_ms': 1793050000000,
            'share_text': '分享 https://v.douyin.com/example/', 'logs': ['媒体下载：HTTP 404']}


def test_failure_reports_require_session_are_isolated_and_idempotent():
    with TestClient(app) as c:
        body = payload()
        assert c.post('/v2/diagnostics/extractions', json=body).status_code == 401
        _, a = register(c); _, b = register(c)
        r = c.post('/v2/diagnostics/extractions', headers=a, json=body)
        assert r.status_code == 200
        assert r.json()['id'] == body['id']
        assert c.post('/v2/diagnostics/extractions', headers=a, json=body).json() == r.json()
        assert len(c.get('/v2/diagnostics/extractions', headers=a).json()) == 1
        assert c.get('/v2/diagnostics/extractions', headers=b).json() == []
        assert c.get('/v2/diagnostics/extractions/' + body['id'], headers=b).status_code == 404
        detail = c.get('/v2/diagnostics/extractions/' + body['id'], headers=a).json()
        assert detail['share_text'] == body['share_text'] and 'HTTP 404' in detail['logs'][0]


def test_failure_report_redacts_secrets_and_rejects_oversize_payload():
    with TestClient(app) as c:
        _, auth = register(c)
        body = payload()
        body['share_text'] += ' password=do-not-store'
        body['logs'] += ['Cookie: secret-cookie', 'Authorization: Bearer secret-auth', 'GET https://cdn.invalid/private?token=secret']
        assert c.post('/v2/diagnostics/extractions', headers=auth, json=body).status_code == 200
        result = c.get('/v2/diagnostics/extractions/' + body['id'], headers=auth).text
        for secret in ('do-not-store', 'secret-cookie', 'secret-auth', 'cdn.invalid/private'):
            assert secret not in result
        body['id'] = str(uuid4()); body['share_text'] = 'x' * 10001
        assert c.post('/v2/diagnostics/extractions', headers=auth, json=body).status_code == 422
