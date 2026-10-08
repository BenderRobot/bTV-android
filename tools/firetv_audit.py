#!/usr/bin/env python3
"""Explore a Fire TV app with ADB and produce an OpenAI vision audit."""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
REPORT_ROOT = PROJECT_ROOT / "audit-reports"
DEFAULT_PACKAGE = "com.btvplayer"
DEFAULT_MODEL = "gpt-4o-mini"
EXPECTED_SCREENS = [
    "home",
    "favorites",
    "live",
    "movies",
    "series",
    "replay",
    "settings",
]
SCREEN_TYPES = {
    "login",
    "home",
    "favorites",
    "live",
    "movies",
    "series",
    "replay",
    "browse_other",
    "settings",
    "player",
    "dialog",
    "mini_player",
    "other",
}
KEY_EVENTS = {
    "UP": "19",
    "DOWN": "20",
    "LEFT": "21",
    "RIGHT": "22",
    "ENTER": "66",
    "BACK": "4",
}
SEVERITIES = {"critical", "high", "medium", "low", "info"}


class AuditError(RuntimeError):
    """An actionable error while communicating with ADB or OpenAI."""


def find_adb() -> str:
    candidates = [
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe",
        Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools" / "adb.exe",
        Path(os.environ.get("ANDROID_SDK_ROOT", "")) / "platform-tools" / "adb.exe",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    adb = shutil.which("adb")
    if adb:
        return adb
    raise AuditError("adb.exe introuvable. Installe Android SDK Platform Tools ou ajoute adb au PATH.")


def run_adb(adb: str, serial: str, *args: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    command = [adb, "-s", serial, *args]
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise AuditError(f"Échec ADB ({' '.join(args)}): {detail or f'code {result.returncode}'}")
    return result


def run_adb_bytes(adb: str, serial: str, *args: str, timeout: int = 30) -> bytes:
    command = [adb, "-s", serial, *args]
    result = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
    if result.returncode != 0:
        detail = result.stderr.decode(errors="replace").strip()
        raise AuditError(f"Échec ADB ({' '.join(args)}): {detail or f'code {result.returncode}'}")
    if not result.stdout:
        raise AuditError("ADB n’a renvoyé aucune donnée pour la capture d’écran.")
    return result.stdout


def connected_devices(adb: str) -> list[str]:
    result = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15, check=False)
    if result.returncode != 0:
        raise AuditError(f"Impossible de lister les appareils ADB : {result.stderr.strip()}")
    return [
        line.split()[0]
        for line in result.stdout.splitlines()[1:]
        if len(line.split()) >= 2 and line.split()[1] == "device"
    ]


def select_device(adb: str, requested: str | None) -> str:
    devices = connected_devices(adb)
    if requested and requested not in devices:
        result = subprocess.run(
            [adb, "connect", requested], capture_output=True, text=True, timeout=20, check=False
        )
        if result.returncode != 0:
            raise AuditError(f"Connexion ADB impossible vers {requested} : {result.stderr.strip()}")
        devices = connected_devices(adb)
        if requested not in devices:
            raise AuditError(
                f"{requested} n’apparaît pas comme appareil ADB prêt. "
                "Vérifie le débogage ADB et accepte la demande d’autorisation sur la Fire TV."
            )
        return requested
    if requested:
        return requested
    if len(devices) == 1:
        return devices[0]
    if not devices:
        raise AuditError(
            "Aucune Fire TV connectée. Lance `adb connect IP:5555`, puis relance l’audit."
        )
    raise AuditError(
        "Plusieurs appareils ADB sont connectés. Précise lequel avec --device <serial>."
    )


def parse_audit(content: str) -> dict[str, Any]:
    try:
        audit = json.loads(content)
    except json.JSONDecodeError as error:
        raise AuditError(f"Réponse JSON OpenAI invalide : {error}") from error
    if not isinstance(audit, dict):
        raise AuditError("La réponse OpenAI doit être un objet JSON.")

    screen_type = audit.get("screen_type")
    if screen_type not in SCREEN_TYPES:
        raise AuditError(f"Type d’écran OpenAI invalide : {screen_type!r}")
    if not isinstance(audit.get("screen_name"), str) or not audit["screen_name"].strip():
        raise AuditError("La réponse OpenAI ne contient pas de nom d’écran.")
    for score_name in ("visual_score", "usability_score", "functionality_score"):
        score = audit.get(score_name)
        if not isinstance(score, (int, float)) or isinstance(score, bool) or not 1 <= score <= 5:
            raise AuditError(f"Score OpenAI invalide pour {score_name} : {score!r}")

    issues = audit.get("issues")
    if not isinstance(issues, list):
        raise AuditError("La liste issues de la réponse OpenAI est invalide.")
    for issue in issues:
        if not isinstance(issue, dict) or issue.get("severity") not in SEVERITIES:
            raise AuditError("Une anomalie OpenAI n’a pas de sévérité reconnue.")
        if not all(isinstance(issue.get(field), str) for field in ("title", "description", "recommendation")):
            raise AuditError("Une anomalie OpenAI ne contient pas tous ses champs.")
        confidence = issue.get("confidence")
        if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
            raise AuditError("Une anomalie OpenAI a un niveau de confiance invalide.")

    next_key = audit.get("next_key")
    if next_key not in {*KEY_EVENTS, "STOP"}:
        raise AuditError(f"Touche de navigation OpenAI invalide : {next_key!r}")
    if not isinstance(audit.get("stop"), bool):
        raise AuditError("La réponse OpenAI ne précise pas si l’exploration doit s’arrêter.")
    return audit


def request_openai_audit(
    screenshot: bytes,
    model: str,
    api_key: str,
    visited_screens: set[str],
    allow_playback: bool,
) -> dict[str, Any]:
    expected = EXPECTED_SCREENS + (["player"] if allow_playback else [])
    play_rule = (
        "L’utilisateur a autorisé l’ouverture du lecteur : tu peux sélectionner un contenu "
        "une seule fois pour auditer le lecteur, mais ne modifies aucun réglage ni favori."
        if allow_playback
        else "N’ouvre jamais un contenu et ne démarre jamais de lecture. Le lecteur sera signalé comme non audité."
    )
    prompt = f"""
Tu audites l’interface actuelle d’une application Android TV / Fire TV. Analyse la capture
jointe pour sa qualité commercialisable sur téléviseur : hiérarchie visuelle, lisibilité à
distance, cohérence, focus D-pad visible, navigation, libellés, états vides/chargement/erreur
et actions compréhensibles. N’infère pas le comportement d’un serveur ou d’un bouton qui
n’est pas visible/testé : le score de fonctionnalité porte uniquement sur les affordances
et interactions effectivement observables.

Écrans principaux attendus : {", ".join(expected)}.
Écrans déjà visités : {", ".join(sorted(visited_screens)) or "aucun"}.
Choisis au plus UNE touche D-pad pour atteindre un écran principal encore non visité,
ou STOP si l’écran courant nécessite une connexion, si l’étape demanderait une action
risquée, ou si tu ne peux pas poursuivre sûrement. Un écran de connexion doit toujours
arrêter l’exploration : ne saisis et ne demandes aucun identifiant. N’ouvre jamais les
actions de déconnexion, suppression, compte ou changement de réglages. {play_rule}
Les touches autorisées sont UP, DOWN, LEFT, RIGHT, ENTER, BACK, STOP. ENTER ne doit
servir qu’à ouvrir une rubrique de navigation identifiable, pas à valider une action
destructive, un changement de préférence, ni un dialogue de lecture non autorisé.

Retourne uniquement un objet JSON valide avec ces champs :
{{
  "screen_type": "login|home|favorites|live|movies|series|replay|browse_other|settings|player|dialog|mini_player|other",
  "screen_name": "nom court en français",
  "summary": "observation concise de l’écran",
  "visual_score": 1,
  "usability_score": 1,
  "functionality_score": 1,
  "issues": [
    {{"severity":"critical|high|medium|low|info","title":"...","description":"...","recommendation":"...","confidence":0.8}}
  ],
  "next_key": "UP|DOWN|LEFT|RIGHT|ENTER|BACK|STOP",
  "next_action_reason": "raison de la prochaine touche ou de l’arrêt",
  "stop": false
}}
Les scores sont des entiers de 1 (bloquant/très insuffisant) à 5 (très bon). Signale un
problème uniquement s’il est visible ou directement déductible de l’écran, indique les
incertitudes et ne répète pas une anomalie déjà signalée sur cet écran.
""".strip()
    image_url = "data:image/png;base64," + base64.b64encode(screenshot).decode("ascii")
    body = json.dumps(
        {
            "model": model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {"type": "image_url", "image_url": {"url": image_url, "detail": "high"}},
                    ],
                }
            ],
            "response_format": {"type": "json_object"},
            "temperature": 0.2,
            "max_tokens": 1600,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        "https://api.openai.com/v1/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise AuditError(f"Erreur API OpenAI HTTP {error.code} : {detail}") from error
    except urllib.error.URLError as error:
        raise AuditError(f"Connexion à l’API OpenAI impossible : {error.reason}") from error
    try:
        content = result["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as error:
        raise AuditError("Réponse OpenAI inattendue : aucun contenu d’audit.") from error
    if not isinstance(content, str):
        raise AuditError("Réponse OpenAI inattendue : le contenu d’audit n’est pas du texte.")
    return parse_audit(content)


def write_reports(
    report_dir: Path,
    model: str,
    device: str,
    package: str,
    records: list[dict[str, Any]],
    stopped_reason: str,
    allow_playback: bool,
) -> tuple[Path, Path]:
    report_dir.mkdir(parents=True, exist_ok=True)
    unique_records: dict[str, dict[str, Any]] = {}
    for record in records:
        unique_records.setdefault(record["screen_type"], record)
    target_screens = EXPECTED_SCREENS + (["player"] if allow_playback else [])
    missing = [screen for screen in target_screens if screen not in unique_records]
    assessments = list(unique_records.values())
    average = (
        sum(
            (item["visual_score"] + item["usability_score"] + item["functionality_score"]) / 3
            for item in assessments
        )
        / len(assessments)
        if assessments
        else 0
    )
    issues = [
        (record["screen_name"], issue)
        for record in assessments
        for issue in record["issues"]
    ]
    if any(issue["severity"] in {"critical", "high"} for _, issue in issues) or average < 3:
        readiness = "Des corrections sont prioritaires"
    else:
        readiness = "À valider manuellement avant commercialisation"

    data = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "device": device,
        "package": package,
        "model": model,
        "allow_playback": allow_playback,
        "readiness": readiness,
        "average_score": round(average, 2),
        "observed_screen_types": list(unique_records),
        "unobserved_target_screens": missing,
        "stopped_reason": stopped_reason,
        "steps": records,
    }
    json_path = report_dir / "report.json"
    json_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Audit de l’application Fire TV",
        "",
        f"- Généré : {data['generated_at']}",
        f"- Appareil : `{device}`",
        f"- Package : `{package}`",
        f"- Modèle : `{model}`",
        f"- Écrans distincts observés : {len(assessments)}",
        f"- Score moyen indicatif : **{average:.2f}/5**",
        f"- Conclusion : **{readiness}**",
        "",
        "> L’analyse porte sur les captures et la navigation effectivement observée. "
        "Elle ne remplace ni des tests fonctionnels complets (API, lecture vidéo, compte), "
        "ni une recette humaine.",
        "",
        "## Couverture",
        "",
        f"- Types d’écran observés : {', '.join(data['observed_screen_types']) or 'aucun'}.",
        f"- Types attendus non observés : {', '.join(missing) or 'aucun des types configurés'}.",
        f"- Fin de l’exploration : {stopped_reason}",
        "- L’outil ne saisit ni ne transmet directement d’identifiants ; les captures peuvent "
        "néanmoins contenir des informations visibles à l’écran.",
        "- Les captures d’écran sont envoyées à l’API OpenAI et conservées localement avec ce rapport.",
        "",
        "## Résultats par écran",
        "",
        "| Écran | Visuel | Ergonomie | Fonctionnalité visible | Résumé |",
        "|---|---:|---:|---:|---|",
    ]
    for screen_type, record in unique_records.items():
        summary = record["summary"].replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {record['screen_name']} (`{screen_type}`) | {record['visual_score']}/5 "
            f"| {record['usability_score']}/5 | {record['functionality_score']}/5 | {summary} |"
        )
    lines.extend(["", "## Anomalies et recommandations", ""])
    if not issues:
        lines.append("Aucune anomalie n’a été signalée sur les écrans observés.")
    for screen_name, issue in issues:
        lines.extend(
            [
                f"### [{issue['severity'].upper()}] {issue['title']} — {screen_name}",
                "",
                issue["description"],
                "",
                f"**Correction suggérée :** {issue['recommendation']}",
                "",
                f"Confiance de l’analyse IA : {issue['confidence']:.0%}.",
                "",
            ]
        )
    lines.extend(["## Parcours d’exploration", ""])
    for record in records:
        action = record.get("next_action_reason", "aucune action")
        lines.append(f"- Étape {record['step']} — **{record['screen_name']}** : {action}")
    markdown_path = report_dir / "report.md"
    markdown_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return markdown_path, json_path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Explore une application sur Fire TV et génère un audit visuel assisté par OpenAI."
    )
    parser.add_argument("--device", help="Serial ADB ou adresse réseau, par exemple 192.168.1.69:5555.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help=f"Package Android (défaut : {DEFAULT_PACKAGE}).")
    parser.add_argument("--model", default=os.environ.get("OPENAI_MODEL", DEFAULT_MODEL), help="Modèle vision OpenAI.")
    parser.add_argument("--max-steps", type=int, default=30, help="Nombre maximal d’écrans analysés (1 à 100).")
    parser.add_argument("--wait", type=float, default=3.0, help="Délai après lancement de l’application, en secondes.")
    parser.add_argument(
        "--allow-playback",
        action="store_true",
        help="Autorise l’agent à sélectionner un contenu et à lancer la lecture.",
    )
    parser.add_argument(
        "--confirm-openai-upload",
        action="store_true",
        help="Confirme explicitement l’envoi des captures d’écran à l’API OpenAI.",
    )
    args = parser.parse_args()
    if not 1 <= args.max_steps <= 100:
        parser.error("--max-steps doit être compris entre 1 et 100.")
    if args.wait < 0:
        parser.error("--wait ne peut pas être négatif.")
    if not args.confirm_openai_upload:
        parser.error(
            "Les captures seront envoyées à OpenAI. Relance avec --confirm-openai-upload "
            "après avoir vérifié qu’elles ne contiennent pas d’information sensible."
        )
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        parser.error("La variable d’environnement OPENAI_API_KEY n’est pas définie.")

    adb = find_adb()
    device = select_device(adb, args.device)
    report_dir = REPORT_ROOT / datetime.now().strftime("firetv-%Y%m%d-%H%M%S")
    captures_dir = report_dir / "captures"
    captures_dir.mkdir(parents=True, exist_ok=False)

    print(f"Appareil ADB : {device}")
    print(f"Lancement de {args.package}…")
    launch = run_adb(adb, device, "shell", "monkey", "-p", args.package, "1")
    if "No activities found to run" in launch.stdout:
        raise AuditError(f"Aucune activité lançable trouvée pour le package {args.package}.")
    time.sleep(args.wait)

    records: list[dict[str, Any]] = []
    visited: set[str] = set()
    stopped_reason = "Limite maximale d’étapes atteinte."
    try:
        for step in range(1, args.max_steps + 1):
            screenshot = run_adb_bytes(adb, device, "exec-out", "screencap", "-p", timeout=30)
            audit = request_openai_audit(screenshot, args.model, api_key, visited, args.allow_playback)
            audit["step"] = step
            screen_type = audit["screen_type"]
            audit["screenshot"] = f"captures/step_{step:02d}_{screen_type}.png"
            (report_dir / audit["screenshot"]).write_bytes(screenshot)
            records.append(audit)
            visited.add(screen_type)
            print(
                f"[{step}/{args.max_steps}] {audit['screen_name']} — "
                f"visuel {audit['visual_score']}/5, ergonomie {audit['usability_score']}/5, "
                f"fonctionnalité {audit['functionality_score']}/5"
            )
            print(f"  {audit['summary']}")

            expected = set(EXPECTED_SCREENS + (["player"] if args.allow_playback else []))
            if audit["screen_type"] == "login":
                stopped_reason = "Écran de connexion détecté ; l’outil ne saisit pas d’identifiants."
                break
            if audit["stop"] or audit["next_key"] == "STOP":
                stopped_reason = audit["next_action_reason"] or "L’agent ne peut pas continuer sans risque."
                break
            if expected.issubset(visited):
                stopped_reason = "Tous les types d’écran principaux configurés ont été observés."
                break
            key = audit["next_key"]
            if key not in KEY_EVENTS:
                stopped_reason = "L’agent n’a pas proposé de touche de navigation autorisée."
                break
            run_adb(adb, device, "shell", "input", "keyevent", KEY_EVENTS[key])
            time.sleep(0.8)
    finally:
        markdown_path, json_path = write_reports(
            report_dir, args.model, device, args.package, records, stopped_reason, args.allow_playback
        )
        print(f"\nRapport Markdown : {markdown_path}")
        print(f"Rapport JSON : {json_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AuditError as error:
        print(f"Erreur d’audit : {error}", file=sys.stderr)
        raise SystemExit(1)
