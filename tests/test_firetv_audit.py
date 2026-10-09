import importlib.util
import json
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, patch


SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "firetv_audit.py"
SPEC = importlib.util.spec_from_file_location("firetv_audit", SCRIPT)
firetv_audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(firetv_audit)


class ParseAuditTests(unittest.TestCase):
    def audit(self, **changes):
        result = {
            "screen_type": "home",
            "screen_name": "Accueil",
            "summary": "Menu principal.",
            "visual_score": 4,
            "usability_score": 4,
            "functionality_score": 3,
            "issues": [],
            "next_key": "RIGHT",
            "next_action_reason": "Ouvrir la rubrique suivante.",
            "stop": False,
        }
        result.update(changes)
        return firetv_audit.parse_audit(__import__("json").dumps(result))

    def test_accepts_valid_audit(self):
        self.assertEqual(self.audit()["screen_type"], "home")

    def test_rejects_invalid_screen_type(self):
        with self.assertRaises(firetv_audit.AuditError):
            self.audit(screen_type="unknown")

    def test_rejects_out_of_range_score(self):
        with self.assertRaises(firetv_audit.AuditError):
            self.audit(visual_score=6)

    def test_rejects_unapproved_key(self):
        with self.assertRaises(firetv_audit.AuditError):
            self.audit(next_key="DELETE")

    def test_rejects_malformed_issue(self):
        with self.assertRaises(firetv_audit.AuditError):
            self.audit(issues=[{"severity": "high"}])

    def test_report_marks_unvisited_screens(self):
        record = {
            "step": 1,
            "screen_type": "home",
            "screen_name": "Accueil",
            "summary": "Menu principal.",
            "visual_score": 4,
            "usability_score": 4,
            "functionality_score": 3,
            "issues": [],
            "next_action_reason": "Navigation.",
        }
        with TemporaryDirectory() as temp_dir:
            markdown_path, json_path = firetv_audit.write_reports(
                Path(temp_dir), "test-model", "device", "com.test.app", [record], "Fin.", False
            )
            markdown = markdown_path.read_text(encoding="utf-8")
            self.assertIn("favorites, live, movies", markdown)
            self.assertIn("favorites", markdown)
            self.assertTrue(json_path.is_file())

    def test_failed_report_marks_audit_incomplete(self):
        with TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            captures_dir = report_dir / "captures"
            captures_dir.mkdir()
            (captures_dir / "step_01.png").write_bytes(b"capture")

            markdown_path, json_path = firetv_audit.write_reports(
                report_dir,
                "test-model",
                "device",
                "com.test.app",
                [],
                "Erreur API.",
                False,
                "Crédits API épuisés.",
            )

            markdown = markdown_path.read_text(encoding="utf-8")
            data = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertIn("Audit incomplet", markdown)
            self.assertIn("Crédits API épuisés", markdown)
            self.assertEqual(data["status"], "failed")
            self.assertEqual(data["captures"], ["captures/step_01.png"])


class LaunchApplicationTests(unittest.TestCase):
    def test_default_model_uses_gemini_3_8_flash(self):
        self.assertEqual(firetv_audit.DEFAULT_MODEL, "gemini-3.8-flash")

    def test_suggests_supported_model_when_model_is_retired(self):
        detail = json.dumps(
            {"error": {"message": "This model is no longer available to new users."}}
        )
        message = firetv_audit.gemini_http_error_message(404, detail)
        self.assertIn("--model gemini-3.8-flash", message)

    def test_resolve_launcher_component_ignores_package_manager_metadata(self):
        output = "priority=0 preferredOrder=0 match=0x108000\ncom.btvplayer/com.btv.MainActivity\n"
        self.assertEqual(
            firetv_audit.resolve_launcher_component(output, "com.btvplayer"),
            "com.btvplayer/com.btv.MainActivity",
        )

    def test_gemini_request_uses_a_valid_encoded_url_and_passes_image(self):
        response_content = {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {
                                "text": json.dumps(
                                    {
                                        "screen_type": "home",
                                        "screen_name": "Accueil",
                                        "summary": "Menu.",
                                        "visual_score": 4,
                                        "usability_score": 4,
                                        "functionality_score": 3,
                                        "issues": [],
                                        "next_key": "STOP",
                                        "next_action_reason": "Terminé.",
                                        "stop": True,
                                    }
                                )
                            }
                        ]
                    }
                }
            ]
        }
        response = MagicMock()
        response.__enter__.return_value.read.return_value = json.dumps(response_content).encode()
        with patch.object(firetv_audit.urllib.request, "urlopen", return_value=response) as urlopen:
            result = firetv_audit.request_gemini_audit(
                b"png-image", "gemini-2.5-flash", "test key+/=", set(), False
            )

        request = urlopen.call_args.args[0]
        self.assertEqual(
            request.full_url,
            "https://generativelanguage.googleapis.com/v1beta/models/"
            "gemini-2.5-flash:generateContent?key=test+key%2B%2F%3D",
        )
        body = json.loads(request.data)
        self.assertEqual(body["generationConfig"]["responseMimeType"], "application/json")
        self.assertEqual(body["contents"][0]["parts"][1]["inlineData"]["mimeType"], "image/png")
        self.assertEqual(result["screen_type"], "home")

    def test_explains_gemini_http_error(self):
        detail = json.dumps(
            {"error": {"message": "Invalid API key"}}
        )
        message = firetv_audit.gemini_http_error_message(400, detail)
        self.assertIn("Erreur API Gemini HTTP 400", message)
        self.assertIn("Invalid API key", message)

    def test_resolve_launcher_component_rejects_a_different_package(self):
        self.assertIsNone(
            firetv_audit.resolve_launcher_component("com.other/.MainActivity\n", "com.btvplayer")
        )

    @patch.object(firetv_audit, "run_adb")
    def test_launches_resolved_leanback_activity_without_monkey(self, run_adb):
        run_adb.side_effect = [
            subprocess.CompletedProcess([], 0, "com.btvplayer/com.btv.MainActivity\n", ""),
            subprocess.CompletedProcess([], 0, "Starting: Intent { cmp=com.btvplayer/com.btv.MainActivity }\n", ""),
        ]

        firetv_audit.launch_application("adb", "firetv", "com.btvplayer")

        self.assertEqual(
            run_adb.call_args_list[1].args,
            ("adb", "firetv", "shell", "am", "start", "-n", "com.btvplayer/com.btv.MainActivity"),
        )


if __name__ == "__main__":
    unittest.main()
