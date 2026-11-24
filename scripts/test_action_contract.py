import importlib.util
import json
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-action-contract.py")
SPEC = importlib.util.spec_from_file_location("check_action_contract", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class ActionContractTests(unittest.TestCase):
    def setUp(self):
        self.contract = json.dumps({
            "protocolVersion": 3,
            "capabilities": [
                {
                    "id": "open_settings",
                    "minimumVersion": 3,
                    "sequence": "terminal",
                    "requiresUnlock": True,
                }
            ],
        })
        self.backend = (
            "export const LATEST_ACTION_PROTOCOL_VERSION = 3 as const;\n"
            "  open_settings: { minimumVersion: 3, sequencePosition: \"terminal\" }\n"
        )
        self.android = (
            "const val ACTION_PROTOCOL_VERSION = 3\n"
            "        is PhoneAction.OpenSettings -> Capability(\"open_settings\", 3, true, "
            "SequencePosition.TERMINAL)\n"
        )

    def test_matching_contract_passes(self):
        self.assertEqual(CHECKER.violations(self.contract, self.backend, self.android), [])

    def test_version_and_metadata_drift_fail(self):
        failures = CHECKER.violations(
            self.contract,
            self.backend.replace("= 3", "= 2", 1),
            self.android.replace(", 3, true,", ", 2, false,"),
        )
        self.assertIn("Backend action protocol version differs from the canonical contract", failures)
        self.assertIn("Android capability metadata differs from scripts/action-capabilities.json", failures)

    def test_unknown_fields_fail(self):
        value = json.loads(self.contract)
        value["capabilities"][0]["intent"] = "android.settings.SETTINGS"
        self.assertIn(
            "Action capability contract has an invalid capability entry",
            CHECKER.violations(json.dumps(value), self.backend, self.android),
        )


if __name__ == "__main__":
    unittest.main()
