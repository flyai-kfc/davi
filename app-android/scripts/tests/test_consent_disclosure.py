"""앱 동의 문안과 처리방침 원본의 공급자·버전 동기화를 키 없이 확인한다(#502)."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


APP_ROOT = Path(__file__).resolve().parents[2]


class ConsentDisclosureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = ET.parse(APP_ROOT / "app/src/main/res/values/strings.xml").getroot()
        cls.strings = {item.attrib["name"]: item.text or "" for item in root.findall("string")}
        cls.policy = (APP_ROOT.parent / "docs/PRIVACY-POLICY.md").read_text(encoding="utf-8")

    def test_policy_and_app_share_v3(self):
        match = re.search(r"\*\*문서 버전\*\* `v(\d+)`", self.policy)
        self.assertIsNotNone(match)
        self.assertEqual(self.strings["consent_version"], "3")
        self.assertEqual(match.group(1), self.strings["consent_version"])

    def test_screen_voice_and_third_party_disclose_openai_retention(self):
        for key in ("consent_screen_detail", "consent_voice_detail", "consent_third_party_body"):
            with self.subTest(key=key):
                self.assertIn("OpenAI", self.strings[key])
                self.assertIn("악용 방지", self.strings[key])
        self.assertIn("| OpenAI (현재 release", self.policy)
        self.assertNotIn("현재 Google Gemini", self.policy)

    def test_audio_provider_is_distinct_from_davi_server(self):
        voice = self.strings["consent_voice_detail"]
        self.assertIn("시스템 음성인식", voice)
        self.assertIn("기기 제조사·구글", voice)
        self.assertIn("전송될 수 있습니다", voice)
        self.assertIn("다비 서버가 받거나 저장하지 않습니다", voice)
        self.assertNotIn("휴대폰이 스스로", voice)


if __name__ == "__main__":
    unittest.main()
