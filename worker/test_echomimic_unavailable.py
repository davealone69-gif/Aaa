import importlib.util
import os
import unittest
from pathlib import Path

class EchoMimicUnavailableStateTest(unittest.TestCase):
    def test_missing_checkout_and_weights_are_not_ready(self):
        path = Path(__file__).with_name("echomimic_video_worker.py")
        spec = importlib.util.spec_from_file_location("echomimic_video_worker", path)
        module = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        spec.loader.exec_module(module)
        result = module.health()
        self.assertFalse(result["ready"])
        self.assertIn(result["state"], {"NOT_INSTALLED", "UNAVAILABLE", "INSUFFICIENT_RESOURCES"})
        self.assertEqual(result["provider"], "echomimicv3-flash")

if __name__ == "__main__":
    unittest.main()
