"""Deployment must expose only account endpoints with application authentication."""
import re
from pathlib import Path


def test_default_server_retires_all_legacy_root_routes():
    config = Path('deploy/tx-https/getaudio.conf').read_text()
    assert 'include /etc/nginx/snippets/getaudio-prefix.conf;' in config
    assert 'proxy_pass' not in config, 'Only the project-prefixed snippet may proxy to GetAudio'
    assert 'auth_basic' not in config, 'The obsolete root must not challenge for credentials'
    assert 'location = /health' not in config
    assert 'location ~ ^/(account/' not in config
    assert config.count('location / { return 404; }') == 2
    assert 'location /.well-known/acme-challenge/' in config


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
