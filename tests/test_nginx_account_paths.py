"""Deployment must expose only account endpoints with application authentication."""
import re
from pathlib import Path


def test_nginx_account_route_allowlist():
    config = Path('deploy/tx-https/getaudio.conf').read_text()
    pattern = re.search(r'location ~ (\S+) \{', config).group(1)
    for path in ['/account/register', '/account/login', '/account/me', '/account/logout',
                 '/account/platforms', '/account/platform-bindings',
                 '/account/platform-auth/start', '/account/platform-auth/status',
                 '/account/platform-auth/image', '/account/platform-auth/complete',
                 '/account/platform-auth/cancel', '/account/platform-auth/douyin/callback',
                 '/account/platform-bindings/bilibili/unbind', '/account/platform-bindings/douyin/unbind',
                 '/v2/sync', '/v2/platforms/qr/abc/image', '/agent/v2/actions']:
        assert re.search(pattern, path), path
    for path in ['/tracks', '/sync', '/audio/1', '/credentials', '/account/login-extra', '/agent/plan', '/account/platform-auth/complete-extra',
                 '/account/platform-bindings/other/unbind']:
        assert not re.search(pattern, path), path
    assert 'auth_basic_user_file /etc/nginx/getaudio.htpasswd;' in config
    assert 'proxy_set_header X-Forwarded-For $remote_addr;' in config
    assert 'proxy_pass http://127.0.0.1:8800;' in config


def test_prefixed_account_allowlist_is_bounded_and_preserves_legacy_protection():
    config = Path('deploy/tx-https/getaudio-prefix.conf').read_text()
    pattern = re.search(r'location ~ (\S+) \{', config).group(1)
    for path in ['/getaudio/account/login', '/getaudio/account/me', '/getaudio/v2/sync',
                 '/getaudio/v2/audio/1', '/getaudio/agent/v2/actions']:
        assert re.search(pattern, path), path
    for path in ['/account/login', '/getaudio-other/v2/sync', '/getaudio/account/login-extra',
                 '/getaudio/audio/1', '/getaudio/login/cookies', '/getvideo/v2/sync']:
        assert not re.search(pattern, path), path
    assert 'location ^~ /getaudio/' in config
    assert 'auth_basic_user_file /etc/nginx/getaudio.htpasswd;' in config
    assert 'location = /getaudio/account/platform-auth/douyin/callback' in config
