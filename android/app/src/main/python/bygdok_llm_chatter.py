import logging
import os
import sys
import tempfile
import threading


_stop_event = threading.Event()


def run(config_path, runtime_dir):
    os.makedirs(runtime_dir, exist_ok=True)
    # Native environment writes race with Android ProcessBuilder.environment().
    tempfile.tempdir = runtime_dir
    logging.basicConfig(
        filename=os.path.join(runtime_dir, "bridge.log"),
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
    )
    sys.argv = ["llm_chatter_bridge.py", "--config", config_path]
    from llm_chatter_bridge import main

    main(stop_event=_stop_event)


def reset():
    _stop_event.clear()


def request_stop():
    _stop_event.set()
