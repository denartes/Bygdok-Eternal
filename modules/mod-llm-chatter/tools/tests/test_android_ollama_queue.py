"""Managed Android runner admission without a live provider."""

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import chatter_llm as llm


CONFIG = {
    'Bygdok.Ollama.Managed': '1',
    'LLMChatter.Ollama.BaseUrl': 'http://127.0.0.1:11435',
}


class AndroidOllamaQueueTest(unittest.TestCase):
    def wait_for_waiters(self, count):
        deadline = time.monotonic() + 2
        with llm._local_ollama_condition:
            while len(llm._local_ollama_waiters) != count:
                remaining = deadline - time.monotonic()
                self.assertGreater(remaining, 0, 'Workers did not join the queue')
                llm._local_ollama_condition.wait(min(remaining, .01))

    def test_player_request_passes_queued_background_and_fifo_is_preserved(self):
        order = []
        first_started = threading.Event()
        release = threading.Event()

        def work(label, block=False):
            with llm._local_ollama_slot(CONFIG, 'ollama', label):
                order.append(label)
                if block:
                    first_started.set()
                    if not release.wait(3):
                        raise TimeoutError('Test did not release first request')

        with ThreadPoolExecutor(max_workers=4) as pool:
            first = pool.submit(work, 'memory_generation', True)
            self.assertTrue(first_started.wait(2))
            try:
                background = pool.submit(work, 'memory_summary')
                self.wait_for_waiters(1)
                player = pool.submit(work, 'group_player_msg')
                self.wait_for_waiters(2)
                analysis = pool.submit(work, 'find_addressed_bot')
                self.wait_for_waiters(3)
            finally:
                release.set()
            for future in (first, background, player, analysis):
                future.result(timeout=3)
        self.assertEqual(order, ['memory_generation', 'group_player_msg', 'find_addressed_bot', 'memory_summary'])
        self.assertFalse(llm._local_ollama_active)
        self.assertEqual(llm._local_ollama_waiters, [])

    def test_failures_release_the_runner(self):
        with self.assertRaises(ValueError):
            with llm._local_ollama_slot(CONFIG, 'ollama', 'group_player_msg'):
                raise ValueError('provider failed')
        with llm._local_ollama_slot(CONFIG, 'ollama', 'group_greeting'):
            self.assertTrue(llm._local_ollama_active)
        self.assertFalse(llm._local_ollama_active)

    def test_queue_timeout_reports_failure_and_does_not_release_active_request(self):
        with llm._local_ollama_slot(CONFIG, 'ollama', 'memory_generation'):
            with patch.object(llm.time, 'monotonic', side_effect=[0, 181]):
                with self.assertLogs(llm.logger, level='ERROR') as messages:
                    result = llm._call_target(
                        None, 'prompt', CONFIG, 'ollama', 'model', 100,
                        .8, 'group_player_msg', None, None, False,
                    )
            self.assertIsNone(result)
            self.assertIn('request queue timed out', messages.output[0])
            self.assertTrue(llm._local_ollama_active)
            self.assertEqual(llm._local_ollama_waiters, [])
        self.assertFalse(llm._local_ollama_active)

    def test_request_boundary_holds_slot_and_preserves_arguments_and_response(self):
        arguments = (None, 'prompt', CONFIG, 'ollama', 'model', 100,
                     .8, 'group_player_msg', None, None, False)
        def perform(*actual):
            self.assertTrue(llm._local_ollama_active)
            self.assertEqual(actual, arguments)
            return '{"message":"Hello"}'
        with patch.object(llm, '_perform_call_target', side_effect=perform):
            self.assertEqual(llm._call_target(*arguments), '{"message":"Hello"}')
        self.assertFalse(llm._local_ollama_active)

    def test_remote_and_unmanaged_providers_do_not_wait_for_local_runner(self):
        cases = [
            ({**CONFIG, 'Bygdok.Ollama.Managed': '0'}, 'ollama'),
            ({**CONFIG, 'LLMChatter.Ollama.BaseUrl': 'http://192.168.0.10:11434'}, 'ollama'),
            (CONFIG, 'openai'),
        ]
        with llm._local_ollama_slot(CONFIG, 'ollama', 'memory_generation'):
            with ThreadPoolExecutor(max_workers=1) as pool:
                for config, provider in cases:
                    def work():
                        with llm._local_ollama_slot(config, provider, 'group_player_msg'):
                            return True
                    self.assertTrue(pool.submit(work).result(timeout=1))


if __name__ == '__main__':
    unittest.main()
