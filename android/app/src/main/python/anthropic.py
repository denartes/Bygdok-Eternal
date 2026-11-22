from types import SimpleNamespace

from bygdok_provider_http import APIStatusError, post_json


class Anthropic:
    def __init__(
        self,
        api_key=None,
        base_url="https://api.anthropic.com",
        timeout=600,
        default_headers=None,
        **_options
    ):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.default_headers = dict(default_headers or {})
        self.messages = SimpleNamespace(create=self._create_message)

    def _create_message(self, **kwargs):
        headers = {
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        }
        if self.api_key:
            headers["x-api-key"] = self.api_key
        headers.update(kwargs.pop("extra_headers", {}) or {})
        timeout = kwargs.pop("timeout", self.timeout)
        payload = dict(kwargs)
        payload.update(payload.pop("extra_body", {}) or {})
        return post_json(
            self.base_url + "/v1/messages",
            {**self.default_headers, **headers},
            payload,
            timeout,
        )


__all__ = ["APIStatusError", "Anthropic"]
