import importlib.util
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory


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


if __name__ == "__main__":
    unittest.main()
