"""The same account and media must work on both rollout entrances."""
from uuid import uuid4

from fastapi.testclient import TestClient

from getaudio.api import app
from getaudio.accounts import _windows
from getaudio.config import settings
from getaudio.db import session_scope
from getaudio.models import Track


def test_prefixed_registration_session_media_and_legacy_links():
    with TestClient(app) as client:
        assert client.get('/getaudio/health').status_code == 200
        assert client.get('/getaudio/v2/sync').status_code == 401
        _windows.clear()
        phone = '139' + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
        account = client.post('/getaudio/account/register', json={
            'phone': phone, 'password': 'prefix-test-password',
        })
        assert account.status_code == 200
        user = account.json()
        auth = {'X-GetAudio-Session': user['session_token']}
        assert client.get('/account/me', headers=auth).json()['user_id'] == user['user_id']
        assert client.get('/getaudio/account/me', headers=auth).json()['user_id'] == user['user_id']
        file = settings.audio_dir / f'{uuid4().hex}.mp3'
        file.write_bytes(b'prefix media bytes')
        with session_scope() as session:
            track = Track(owner_scope=user['user_id'], platform='douyin', video_id=uuid4().hex,
                          source_url='https://example.invalid/', audio_path=file.name, updated_seq=900001)
            session.add(track)
            session.flush()
            tid = track.id
        new = client.get('/getaudio/v2/sync', headers=auth).json()['items'][0]
        assert new['download_url'] == f'/getaudio/v2/audio/{tid}'
        assert client.get(new['download_url'], headers=auth).content == b'prefix media bytes'
        old = client.get('/v2/sync', headers=auth).json()['items'][0]
        assert old['download_url'] == f'/v2/audio/{tid}'
        assert client.get('/getaudio/v2/sync/../sync', headers=auth).status_code == 200
        assert client.get('/getaudio-other/health').status_code == 404
        client.post('/getaudio/account/logout', headers=auth)
        assert client.get('/account/me', headers=auth).status_code == 401


def test_prefixed_links_do_not_leak_between_concurrent_requests():
    import asyncio
    import httpx
    from getaudio.public_prefix import public_path

    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='https://testserver') as c:
            results = await asyncio.gather(*[c.get(p) for p in
                ['/getaudio/docs', '/docs', '/getaudio/health', '/health']])
            assert '/getaudio/openapi.json' in results[0].text
            assert "url: '/openapi.json'" in results[1].text
    asyncio.run(run())
    assert public_path('/v2/audio/1') == '/v2/audio/1'


def test_prefixed_slash_redirect_preserves_https_and_namespace():
    with TestClient(app, base_url='https://testserver') as client:
        response = client.get('/getaudio/health/', follow_redirects=False)
        assert response.status_code == 307
        assert response.headers['location'] == 'https://testserver/getaudio/health'
