"""Dual entrance during rollout; no globally mutable public base URL."""
from contextvars import ContextVar

PUBLIC_PREFIX = '/getaudio'
_prefix: ContextVar[str] = ContextVar('getaudio_public_prefix', default='')


def public_path(path: str) -> str:
    """Only application-owned paths go here, never platform URLs."""
    return _prefix.get() + path


class PublicPrefixMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        prefix = ''
        if scope['type'] in ('http', 'websocket') and scope['path'].startswith(PUBLIC_PREFIX + '/'):
            prefix = PUBLIC_PREFIX
            scope = dict(scope)
            # Starlette removes root_path for matching; URLs retain the namespace.
            scope['root_path'] = prefix
        token = _prefix.set(prefix)
        try:
            await self.app(scope, receive, send)
        finally:
            _prefix.reset(token)
