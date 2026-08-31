"""앱 동의 문안과 처리방침 원본의 공급자·버전 동기화를 키 없이 확인한다(#502)."""

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

APP_ROOT = Path(__file__).resolve().parents[2]
PROVIDER_NAMES = {"openai": "OpenAI", "gemini": "Google Gemini", "anthropic": "Anthropic"}


def disclosed_providers(text):
    return {
        name
        for name in PROVIDER_NAMES.values()
        if re.search(rf"\b{re.escape(name)}\b", text, re.ASCII)
    }


def policy_recipients(policy):
    # §7의 보유 설명이나 표 밖의 과거 공급자 언급이 §6 제공처 누락을 가리지 않게 한다.
    section = re.search(r"^## 6\..*?\n(.*?)(?=^## |\Z)", policy, re.MULTILINE | re.DOTALL)
    if section is None:
        return set()
    recipient_cells = [
        line.strip().split("|")[1].strip()
        for line in section.group(1).splitlines()
        if line.strip().startswith("|")
    ]
    return disclosed_providers("\n".join(recipient_cells))


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
        workflow = (APP_ROOT.parent / ".github/workflows/azure-release-deploy.yml").read_text(
            encoding="utf-8"
        )
        provider = re.search(
            r"^\s+RELEASE_DISCLOSED_LLM_PROVIDER:\s*(\w+)\s*$", workflow, re.MULTILINE
        )
        self.assertIsNotNone(provider)
        expected = {PROVIDER_NAMES[provider.group(1)]}
        for key in ("consent_screen_detail", "consent_voice_detail", "consent_third_party_body"):
            with self.subTest(key=key):
                self.assertEqual(disclosed_providers(self.strings[key]), expected)
                self.assertIn("악용 방지", self.strings[key])
        self.assertEqual(policy_recipients(self.policy), expected)

    def test_recipient_comparison_ignores_format_but_not_another_provider(self):
        for name in ("OpenAI", "Anthropic"):
            for cell in (name, f" **{name}** (현재 release) ", f"    {name}    "):
                with self.subTest(name=name, cell=cell):
                    policy = f"## 6. 제3자 제공\n| {cell} | 목적 |\n## 7. 보유\nOpenAI\n"
                    self.assertEqual(policy_recipients(policy), {name})

    def test_audio_provider_is_distinct_from_davi_server(self):
        voice = self.strings["consent_voice_detail"]
        self.assertIn("시스템 음성인식", voice)
        self.assertIn("기기 제조사·구글", voice)
        self.assertIn("전송될 수 있습니다", voice)
        self.assertIn("다비 서버가 받거나 저장하지 않습니다", voice)
        self.assertNotIn("휴대폰이 스스로", voice)


if __name__ == "__main__":
    unittest.main()
