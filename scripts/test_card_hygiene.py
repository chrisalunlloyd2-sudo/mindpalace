import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from scripts.card_hygiene import main, scan_cards


class CardHygieneTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.task_dir = self.root / "cards"
        self.task_dir.mkdir()

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_card(self, name, card, age_hours=0):
        path = self.task_dir / name
        path.write_text(json.dumps(card), encoding="utf-8")
        timestamp = (datetime.now(timezone.utc) - timedelta(hours=age_hours)).timestamp()
        os.utime(path, (timestamp, timestamp))
        return path

    def test_diag_reports_stranded_and_max_retry_cards(self):
        self.write_card("TASK_0001.json", {"status": "in_progress"}, age_hours=30)
        self.write_card("TASK_0002.json", {
            "status": "blocked", "blocked_reason": "dependency", "retry_count": 3,
            "max_retries": 3,
        })

        report = scan_cards(self.task_dir)

        self.assertEqual(["TASK_0001.json"], [item["card"] for item in report["stranded"]])
        self.assertEqual(["TASK_0002.json"], [item["card"] for item in report["max_retry_blocks"]])
        self.assertEqual(2, report["scanned"])

    def test_repair_unblocks_infrastructure_only_once(self):
        card_path = self.write_card("TASK_0003.json", {
            "status": "blocked", "blocked_reason": "infrastructure failure",
            "retry_count": 1, "max_retries": 3,
        })

        first = scan_cards(self.task_dir, quality_tier="repair")
        second = scan_cards(self.task_dir, quality_tier="repair")

        self.assertEqual(["TASK_0003.json"], first["infra_unblocked"])
        self.assertEqual([], second["infra_unblocked"])
        self.assertEqual("queued", json.loads(card_path.read_text())["status"])

    def test_repair_does_not_override_max_retry_or_other_blocks(self):
        self.write_card("TASK_0004.json", {
            "status": "blocked", "blocked_reason": "infrastructure", "retry_count": 3,
            "max_retries": 3,
        })
        self.write_card("TASK_0005.json", {
            "status": "blocked", "blocked_reason": "needs review", "retry_count": 0,
            "max_retries": 3,
        })

        report = scan_cards(self.task_dir, quality_tier="repair")

        self.assertEqual([], report["infra_unblocked"])
        self.assertEqual(["TASK_0004.json"], [item["card"] for item in report["max_retry_blocks"]])

    def test_daily_report_preserves_other_cleanup_reports(self):
        reports = self.root / "reports"
        reports.mkdir()
        cleanup_report = reports / "cleanup-2026-10-04.json"
        cleanup_report.write_text('{"retained": true}\n', encoding="utf-8")

        result = main([
            "diag", "--task-dir", str(self.task_dir), "--report-dir", str(reports),
        ])

        self.assertEqual(0, result)
        self.assertEqual('{"retained": true}\n', cleanup_report.read_text(encoding="utf-8"))
        self.assertTrue((reports / f"card-hygiene-{datetime.now().date().isoformat()}.json").exists())


if __name__ == "__main__":
    unittest.main()
