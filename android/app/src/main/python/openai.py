from types import SimpleNamespace

from bygdok_provider_http import APIStatusError, post_json


class OpenAI:
    def __init__(
        self,
        api_key=None,
        base_url="https://api.openai.com/v1",
        timeout=600,
        default_headers=None,
        **_options
    ):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.default_headers = dict(default_headers or {})
        self.chat = SimpleNamespace(
            completions=SimpleNamespace(create=self._create_chat_completion)
        )

    def _create_chat_completion(self, **kwargs):
        headers = {"content-type": "application/json"}
        if self.api_key:
            headers["authorization"] = "Bearer " + self.api_key
        headers.update(kwargs.pop("extra_headers", {}) or {})
        timeout = kwargs.pop("timeout", self.timeout)
        payload = dict(kwargs)
        payload.update(payload.pop("extra_body", {}) or {})
        return post_json(
            self.base_url + "/chat/completions",
            {**self.default_headers, **headers},
            payload,
            timeout,
        )


__all__ = ["APIStatusError", "OpenAI"]
