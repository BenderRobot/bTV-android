#!/usr/bin/env python3
"""Feature-by-feature audit of bTV on a Fire TV, over ADB, with Gemini vision.

Instead of letting the model wander until it has seen each main screen once,
the audit runs scripted scenarios - one per feature - that know the app's
remote navigation. After every key it waits for the screen to settle (no
capture in the middle of a transition), checks that the app is still in the
foreground and did not crash, then asks Gemini whether the screen shows what
that key was supposed to produce, and what is wrong with it. Where the exact
keys depend on the content (a button to reach, a guide to open), a short
"goal" step lets Gemini choose the keys under strict safety rules.

Nothing is changed in the app: no favourite, setting or PIN is touched, no
account action. Playback scenarios only run with --allow-playback (one
stream at a time, stopped at the end - the IPTV account limits connections).

Report: audit-reports/firetv-audit-<date>/report.md (+ report.json, captures).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
REPORT_ROOT = PROJECT_ROOT / "audit-reports"
DEFAULT_PACKAGE = "com.btvplayer"
DEFAULT_MODEL = "gemini-3.8-flash"
KEY_EVENTS = {
    "UP": "19",
    "DOWN": "20",
    "LEFT": "21",
    "RIGHT": "22",
    "ENTER": "66",
    "BACK": "4",
}
SEVERITIES = ["critical", "high", "medium", "low", "info"]
SEVERITY_RANK = {name: rank for rank, name in enumerate(SEVERITIES)}


class AuditError(RuntimeError):
    """An actionable error while communicating with ADB or Gemini."""


# --------------------------------------------------------------------------- scenarios

@dataclass
class Step:
    """One action and what it should produce.

    keys: D-pad keys pressed in order (a capture follows the last one), or
          ("TEXT", "...") to type into a focused field.
    goal: instead of keys, Gemini chooses up to max_keys keys to reach this
          state (safety rules in the prompt).
    """

    expect: str
    keys: list[Any] = field(default_factory=list)
    goal: str | None = None
    max_keys: int = 6
    settle: float = 4.0


@dataclass
class Scenario:
    id: str
    title: str
    area: str
    steps: list[Step]
    playback: bool = False


def scenarios(allow_playback: bool) -> list[Scenario]:
    """The app's features, with the remote paths of the TV layout.

    Home: tiles Rediffusion, En direct, Favoris (focused at launch), Séries,
    Films; Up = header (Compte, Actualiser, Réglages); Down = "Continuer à
    regarder". Sections: the sidebar has the focus, Right = content, Back =
    sidebar, Back again = Home.
    """
    to_movies = ["RIGHT", "RIGHT", "ENTER"]
    to_series = ["RIGHT", "ENTER"]
    to_live = ["LEFT", "ENTER"]
    to_replay = ["LEFT", "LEFT", "ENTER"]
    items = [
        Scenario("home", "Accueil : tuiles et en-tête", "Accueil", [
            Step("Accueil après le lancement : en-tête (logo, état de l'abonnement, icônes Compte / Actualiser / "
                 "Réglages), cinq tuiles de taille identique, focus visible sur la tuile Favoris."),
            Step("Le focus passe sur la tuile Séries, sans que rien d'autre ne bouge.", ["RIGHT"]),
            Step("Le focus passe sur la tuile Films.", ["RIGHT"]),
            Step("Le focus reste sur Films (dernière tuile) : rien ne se passe ni ne disparaît.", ["RIGHT"]),
            Step("Le focus revient jusqu'à la tuile Rediffusion (première).", ["LEFT", "LEFT", "LEFT", "LEFT"]),
            Step("Le focus monte sur l'icône Compte de l'en-tête, avec son libellé dessous ; les autres icônes "
                 "ne se sont pas déplacées.", ["UP"]),
            Step("Le focus passe sur Actualiser, libellé dessous, sans décaler les icônes voisines.", ["RIGHT"]),
            Step("Le focus passe sur Réglages, libellé dessous.", ["RIGHT"]),
            Step("Retour du focus sur les tuiles.", ["DOWN"]),
        ]),
        Scenario("continue", "Accueil : rangée « Continuer à regarder »", "Accueil", [
            Step("Le focus descend sur la première carte de « Continuer à regarder » (si la rangée existe) ; "
                 "la carte focalisée est entière, cadre compris.", ["DOWN"]),
            Step("Le focus passe à la carte suivante.", ["RIGHT"]),
            Step("Le focus avance encore ; si une carte était hors écran, la rangée défile et le cadre la suit "
                 "sans retard visible ni carte coupée.", ["RIGHT", "RIGHT"]),
            Step("Le focus avance encore (défilement de la rangée si d'autres titres existent).", ["RIGHT"]),
            Step("Le focus revient vers la gauche.", ["LEFT"]),
            Step("Le focus remonte sur les tuiles.", ["UP"]),
        ]),
        Scenario("account", "Accueil : fenêtre Compte", "Accueil", [
            Step("Le focus est sur l'icône Compte.", ["UP"]),
            Step("La fenêtre Compte s'ouvre : utilisateur, statut, expiration, connexions, bouton de fermeture "
                 "focalisé.", ["ENTER"]),
            Step("La fenêtre est fermée, retour à l'accueil avec le focus sur Compte.", ["BACK"]),
        ]),
        Scenario("movies", "Films : catégories, affiches, fiche", "Films", [
            Step("La section Films s'ouvre : catégories à gauche (focus visible), affiches et bandeau de "
                 "détails du titre sélectionné.", to_movies, settle=8.0),
            Step("La catégorie suivante est sélectionnée et le contenu se met à jour.", ["DOWN"], settle=8.0),
            Step("Une autre catégorie est sélectionnée.", ["DOWN"], settle=8.0),
            Step("Le focus passe sur les affiches ; le bandeau montre le titre, ses infos et son résumé.", ["RIGHT"]),
            Step("Le focus avance d'une affiche, le bandeau suit.", ["RIGHT"]),
            Step("Le focus avance encore.", ["RIGHT"]),
            Step("La fenêtre d'informations du film est ouverte (affiche, résumé, réalisateur, casting).",
                 goal="Ouvrir la fenêtre d'informations (bouton « i ») du film sélectionné, en passant par les "
                      "boutons d'action au-dessus des affiches. N'appuie jamais sur ENTER si le focus est sur "
                      "le bouton favori (étoile) ou « vu » (coche).", max_keys=5),
            Step("La fenêtre d'informations est fermée.", ["BACK"]),
            Step("Retour au menu des catégories.", ["BACK"]),
            Step("Retour à l'accueil.", ["BACK"]),
        ]),
        Scenario("series", "Séries : saisons et épisodes", "Séries", [
            Step("La section Séries s'ouvre, focus sur les catégories.", to_series, settle=8.0),
            Step("Le focus passe sur les affiches de séries.", ["RIGHT"]),
            Step("La série s'ouvre sur la liste de ses saisons (ou sur son nouvel épisode si elle vient des "
                 "Nouveautés).", ["ENTER"], settle=8.0),
            Step("La saison s'ouvre sur ses épisodes, focus sur le premier épisode.", ["ENTER"], settle=8.0),
            Step("Le focus passe à l'épisode suivant.", ["RIGHT"]),
            Step("Retour à la liste des saisons.", ["BACK"]),
            Step("Retour à la liste des séries.", ["BACK"]),
            Step("Retour au menu des catégories.", ["BACK"]),
            Step("Retour à l'accueil.", ["BACK"]),
        ]),
        Scenario("search", "Recherche dans une catégorie", "Films", [
            Step("La section Films s'ouvre.", to_movies, settle=8.0),
            Step("Le focus passe sur les affiches.", ["RIGHT"]),
            Step("Le focus monte vers le champ « Rechercher dans cette catégorie » (ou les boutons "
                 "d'action puis le champ).", ["UP", "UP"]),
            Step("Le champ de recherche est en saisie (clavier ou champ actif).", ["ENTER"]),
            Step("Des résultats correspondant à « a » s'affichent.", [("TEXT", "a")], settle=8.0),
            Step("Le clavier est fermé, les résultats restent visibles.", ["BACK"]),
            Step("Retour en arrière dans la section.", ["BACK"]),
            Step("Retour à l'accueil (ou au menu des catégories).", ["BACK", "BACK"]),
        ]),
        Scenario("live", "Direct : chaînes et guide", "Direct", [
            Step("La section Direct s'ouvre : catégories, liste des chaînes avec le programme en cours, panneau "
                 "du guide.", to_live, settle=8.0),
            Step("Une autre catégorie de chaînes est sélectionnée.", ["DOWN"], settle=8.0),
            Step("Le focus passe sur la liste des chaînes ; le panneau de droite montre le guide de la chaîne.",
                 ["RIGHT"], settle=6.0),
            Step("Le focus descend d'une chaîne, le guide suit.", ["DOWN"], settle=6.0),
            Step("Le focus descend encore.", ["DOWN"], settle=6.0),
            Step("Le guide complet (programmes par jour) de la chaîne sélectionnée est affiché.",
                 goal="Ouvrir le guide TV complet de la chaîne sélectionnée (icône calendrier / « Guide » du "
                      "panneau de droite). N'appuie jamais sur ENTER sur une chaîne de la liste (cela lancerait "
                      "la lecture) ni sur le bouton favori (étoile).", max_keys=5),
            Step("Retour depuis le guide.", ["BACK"]),
            Step("Retour au menu des catégories.", ["BACK"]),
            Step("Retour à l'accueil.", ["BACK"]),
        ]),
        Scenario("replay", "Rediffusion : chaînes, jours, programmes", "Rediffusion", [
            Step("La section Rediffusion s'ouvre : chaînes à gauche, jours et programmes au centre, "
                 "détails à droite.", to_replay, settle=8.0),
            Step("Une autre chaîne est sélectionnée, ses programmes se chargent.", ["DOWN"], settle=8.0),
            Step("Le focus passe sur la liste des programmes ; le panneau de droite détaille le programme.",
                 ["RIGHT"], settle=6.0),
            Step("Le focus descend d'un programme.", ["DOWN"]),
            Step("Le jour précédent est affiché (Droite change de jour depuis la liste).", ["RIGHT"], settle=6.0),
            Step("Retour au menu des chaînes.", ["BACK"]),
            Step("Retour à l'accueil.", ["BACK"]),
        ]),
        Scenario("favorites", "Favoris", "Favoris", [
            Step("La section Favoris s'ouvre : rubriques Films / Séries / En direct à gauche.", ["ENTER"], settle=8.0),
            Step("La rubrique suivante est sélectionnée.", ["DOWN"], settle=6.0),
            Step("La rubrique suivante est sélectionnée (les chaînes s'affichent en liste avec le guide).",
                 ["DOWN"], settle=6.0),
            Step("Le focus passe sur le contenu.", ["RIGHT"]),
            Step("Retour au menu.", ["BACK"]),
            Step("Retour à l'accueil.", ["BACK"]),
        ]),
        Scenario("settings", "Réglages : toutes les rubriques", "Réglages", [
            Step("Le focus est sur l'icône Compte.", ["UP"]),
            Step("Le focus est sur Réglages.", ["RIGHT", "RIGHT"]),
            Step("Les Réglages s'ouvrent sur Serveur : adresse, utilisateur, expiration, version, boutons.",
                 ["ENTER"], settle=6.0),
            Step("Rubrique Affichage (thème, couleur, taille du texte).", ["DOWN"]),
            Step("Rubrique Sous-titres (police, couleur, fond, taille, aperçu).", ["DOWN"]),
            Step("Rubrique Lecteur (réserve du direct).", ["DOWN"]),
            Step("Rubrique Contrôle parental.", ["DOWN"]),
            Step("Rubrique Filtrage par langue (liste des langues avec cases).", ["DOWN"], settle=6.0),
            Step("Rubrique Catégories masquées.", ["DOWN"], settle=6.0),
            Step("Retour à l'accueil.", ["BACK"]),
        ]),
    ]
    if allow_playback:
        items += [
            Scenario("vod_player", "Lecteur : film, commandes, menus", "Lecteur", [
                Step("La section Films s'ouvre.", to_movies, settle=8.0),
                Step("Le focus est sur une affiche.", ["RIGHT"]),
                Step("Le lecteur démarre (ou propose de reprendre là où on s'était arrêté).", ["ENTER"],
                     settle=12.0),
                Step("La vidéo est lancée (si une reprise était proposée, OK a choisi « Reprendre »).",
                     ["ENTER"], settle=12.0),
                Step("Les commandes du lecteur s'affichent : titre en haut, barre de progression et temps, "
                     "boutons ronds de lecture à gauche, options en icônes à droite.", ["UP"], settle=2.5),
                Step("Le focus passe d'un bouton à l'autre ; le bouton focalisé est mis en évidence.",
                     ["DOWN", "RIGHT"], settle=2.5),
                Step("Le panneau Infos (affiche, résumé, casting) est ouvert à droite de l'image.",
                     goal="Ouvrir le panneau d'informations du lecteur (bouton « i » / Infos de la rangée de "
                          "boutons). Si les commandes ont disparu, appuie d'abord sur UP. Ne valide aucun autre "
                          "bouton (ni Audio, ni Sous-titres, ni Qualité, ni Suivant).", max_keys=7, settle=2.5),
                Step("Le panneau Infos est fermé.", ["BACK"], settle=2.5),
                Step("Les commandes sont masquées, puis la fenêtre « Quitter la lecture ? » propose Réduire et "
                     "Sortir.", ["BACK", "BACK"], settle=2.5),
                Step("Le lecteur passe en mini-lecteur dans un coin de l'écran précédent.", ["ENTER"], settle=6.0),
            ], playback=True),
            Scenario("live_player", "Lecteur : chaîne en direct", "Lecteur", [
                Step("La section Direct s'ouvre.", to_live, settle=8.0),
                Step("Le focus est sur une chaîne.", ["RIGHT"], settle=6.0),
                Step("La chaîne démarre en plein écran.", ["ENTER"], settle=15.0),
                Step("Les commandes s'affichent : nom de la chaîne et programme en haut, barre de progression "
                     "du programme en bas avec heures de début et de fin.", ["UP"], settle=2.5),
                Step("La fenêtre « Quitter la lecture ? » s'affiche.", ["BACK", "BACK"], settle=2.5),
                Step("Le choix « Sortir » est focalisé.", ["RIGHT"], settle=1.5),
                Step("La lecture s'arrête et l'écran Direct revient.", ["ENTER"], settle=6.0),
            ], playback=True),
        ]
    return items


# --------------------------------------------------------------------------- ADB

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


class Device:
    def __init__(self, adb: str, serial: str, package: str):
        self.adb = adb
        self.serial = serial
        self.package = package

    def run(self, *args: str, timeout: int = 30, check: bool = True) -> str:
        result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout, check=False)
        if check and result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip()
            raise AuditError(f"Échec ADB ({' '.join(args)}): {detail or f'code {result.returncode}'}")
        return result.stdout

    def screenshot(self) -> bytes:
        result = subprocess.run([self.adb, "-s", self.serial, "exec-out", "screencap", "-p"],
                                capture_output=True, timeout=30, check=False)
        if result.returncode != 0 or not result.stdout:
            raise AuditError("Capture d'écran impossible : " + result.stderr.decode(errors="replace").strip())
        return result.stdout

    def key(self, name: str) -> None:
        self.run("shell", "input", "keyevent", KEY_EVENTS[name])

    def text(self, value: str) -> None:
        self.run("shell", "input", "text", value.replace(" ", "%s"))

    def wake(self) -> None:
        # A sleeping Fire TV returns black captures.
        self.run("shell", "input", "keyevent", "224", check=False)

    def force_stop(self) -> None:
        self.run("shell", "am", "force-stop", self.package, check=False)

    def launch(self) -> None:
        errors: list[str] = []
        for category in ("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"):
            out = self.run("shell", "pm", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                           "-c", category, "-p", self.package, check=False)
            component = next((line.strip() for line in out.splitlines()
                              if line.strip().startswith(self.package + "/")), None)
            if not component:
                errors.append(f"aucune activité {category}")
                continue
            started = self.run("shell", "am", "start", "-n", component, check=False)
            if re.search(r"(?m)^\s*Error:", started):
                errors.append(started.strip())
                continue
            return
        raise AuditError(f"Impossible de lancer {self.package} : {'; '.join(errors)}")

    def foreground_lines(self) -> list[str]:
        """Lines naming the window / activity in front, from whichever dumpsys
        this Fire OS fills (`dumpsys window windows` is empty on recent ones)."""
        markers = ("mCurrentFocus", "mFocusedApp", "mResumedActivity", "topResumedActivity", "ResumedActivity:")
        for command in (("dumpsys", "window"), ("dumpsys", "activity", "activities")):
            out = self.run("shell", *command, timeout=30, check=False)
            lines = [line.strip() for line in out.splitlines()
                     if any(marker in line for marker in markers) and "null" not in line]
            if lines:
                return lines
        return []

    def in_foreground(self) -> bool:
        """False only when Android clearly shows another app in front. No
        readable answer (or a window change in progress) counts as present:
        a wrong "left the app" stopped every scenario after its first step."""
        for attempt in range(3):
            lines = self.foreground_lines()
            if not lines or any(self.package in line for line in lines):
                return True
            time.sleep(1.0)  # a dialog or transition may hold the focus for a moment
        print("  (au premier plan : " + " | ".join(lines[:2]) + ")")
        return False

    def clear_logs(self) -> None:
        self.run("logcat", "-c", check=False)

    def crash_logs(self) -> list[str]:
        """FATAL exceptions and ANRs of the app since the last clear_logs()."""
        out = self.run("logcat", "-d", "-v", "brief", "-b", "main,crash,system", timeout=40, check=False)
        lines = out.splitlines()
        found: list[str] = []
        for index, line in enumerate(lines):
            fatal = "FATAL EXCEPTION" in line
            anr = "ANR in" in line and self.package in line
            if fatal or anr:
                block = lines[index:index + 14]
                if fatal and not any(self.package in entry for entry in block):
                    continue
                found.append("\n".join(block))
        return found

    def memory_mb(self) -> float | None:
        out = self.run("shell", "dumpsys", "meminfo", self.package, check=False)
        match = re.search(r"TOTAL PSS:\s*(\d+)", out) or re.search(r"(?m)^\s*TOTAL\s+(\d+)", out)
        return round(int(match.group(1)) / 1024, 1) if match else None

    def jank(self) -> dict[str, Any]:
        out = self.run("shell", "dumpsys", "gfxinfo", self.package, check=False)
        total = re.search(r"Total frames rendered:\s*(\d+)", out)
        janky = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", out)
        p90 = re.search(r"90th percentile:\s*(\d+)ms", out)
        return {
            "frames": int(total.group(1)) if total else None,
            "janky": int(janky.group(1)) if janky else None,
            "janky_percent": float(janky.group(2)) if janky else None,
            "p90_ms": int(p90.group(1)) if p90 else None,
        }

    def reset_gfx(self) -> None:
        self.run("shell", "dumpsys", "gfxinfo", self.package, "reset", check=False)

    def wait_settled(self, timeout: float, interval: float = 0.35) -> tuple[bytes, float, bool]:
        """Captures until two in a row are identical: the screen stopped moving
        (transitions, loading). A playing video never settles: the last
        capture at the timeout is used. Returns (png, seconds, settled)."""
        start = time.monotonic()
        previous = self.screenshot()
        while True:
            time.sleep(interval)
            current = self.screenshot()
            elapsed = time.monotonic() - start
            if hashlib.md5(current).digest() == hashlib.md5(previous).digest():
                return current, elapsed, True
            if elapsed >= timeout:
                return current, elapsed, False
            previous = current


def connected_devices(adb: str) -> list[str]:
    result = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15, check=False)
    if result.returncode != 0:
        raise AuditError(f"Impossible de lister les appareils ADB : {result.stderr.strip()}")
    return [line.split()[0] for line in result.stdout.splitlines()[1:]
            if len(line.split()) >= 2 and line.split()[1] == "device"]


def select_device(adb: str, requested: str | None) -> str:
    devices = connected_devices(adb)
    if requested and requested not in devices:
        subprocess.run([adb, "connect", requested], capture_output=True, text=True, timeout=20, check=False)
        devices = connected_devices(adb)
        if requested not in devices:
            raise AuditError(f"{requested} n'est pas un appareil ADB prêt. Active le débogage ADB et accepte "
                             "l'autorisation sur la Fire TV.")
        return requested
    if requested:
        return requested
    if len(devices) == 1:
        return devices[0]
    if not devices:
        raise AuditError("Aucune Fire TV connectée. Lance `adb connect IP:5555`, puis relance l'audit.")
    raise AuditError("Plusieurs appareils ADB connectés. Précise lequel avec --device <serial>.")


# --------------------------------------------------------------------------- Gemini

APP_CONTEXT = """
Application auditée : bTV, lecteur IPTV pour Fire TV / Android TV (thème sombre, accent vert,
navigation à la télécommande D-pad). Référence de qualité : applications TV premium (Netflix,
Apple TV, Disney+). Critères : lisibilité à 3 m, focus D-pad toujours visible et unique, cohérence
des tailles et alignements, aucun élément coupé ni chevauché, transitions propres, états vides /
chargement / erreur explicites, textes en français corrects.
""".strip()

AUDIT_SCHEMA = """
Retourne UNIQUEMENT un objet JSON (sans markdown) :
{
  "screen_name": "nom court en français de l'écran",
  "summary": "ce que montre l'écran, en une ou deux phrases",
  "expectation": "met | not_met | unclear",
  "expectation_notes": "pourquoi l'attendu est (ou n'est pas) atteint, concrètement",
  "focus_visible": true,
  "focused_element": "élément qui a le focus D-pad, ou \\"aucun\\"",
  "visual_score": 1,
  "usability_score": 1,
  "issues": [
    {"severity": "critical|high|medium|low|info", "title": "...", "description": "...",
     "recommendation": "...", "confidence": 0.8}
  ]
}
Scores entiers de 1 (très insuffisant) à 5 (très bon). N'invente rien : un problème doit être
visible sur la capture. Si une seconde image (l'écran AVANT la touche) est fournie, compare-les
pour juger l'effet de la touche (focus déplacé ? élément décalé ? contenu mis à jour ?).
Distingue un vrai défaut d'un état normal (catégorie vide, chargement bref, contenu du serveur).
""".strip()


def gemini(model: str, api_key: str, prompt: str, images: list[bytes], max_tokens: int = 2000) -> dict[str, Any]:
    parts: list[dict[str, Any]] = [{"text": prompt}]
    for image in images:
        parts.append({"inlineData": {"mimeType": "image/png", "data": base64.b64encode(image).decode("ascii")}})
    body = json.dumps({
        "contents": [{"parts": parts}],
        "generationConfig": {"responseMimeType": "application/json", "temperature": 0.1,
                             "maxOutputTokens": max_tokens},
    }).encode("utf-8")
    url = ("https://generativelanguage.googleapis.com/v1beta/models/"
           f"{urllib.parse.quote(model, safe='')}:generateContent?{urllib.parse.urlencode({'key': api_key})}")
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.loads(response.read().decode("utf-8"))
            text = result["candidates"][0]["content"]["parts"][0]["text"]
            cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
            parsed = json.loads(cleaned)
            if not isinstance(parsed, dict):
                raise ValueError("objet JSON attendu")
            return parsed
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            if error.code in (429, 500, 503) and attempt < 2:
                time.sleep(6 * (attempt + 1))
                last_error = error
                continue
            raise AuditError(f"Erreur API Gemini HTTP {error.code} : {detail[:400]}") from error
        except (urllib.error.URLError, KeyError, IndexError, ValueError, json.JSONDecodeError) as error:
            last_error = error
            time.sleep(3)
    raise AuditError(f"Réponse Gemini inexploitable après 3 essais : {last_error}")


def normalise_audit(raw: dict[str, Any]) -> dict[str, Any]:
    """Tolerant reading: a sloppy field never aborts the whole audit."""
    def score(name: str) -> int | None:
        value = raw.get(name)
        return int(value) if isinstance(value, (int, float)) and not isinstance(value, bool) and 1 <= value <= 5 else None

    issues = []
    for issue in raw.get("issues") or []:
        if not isinstance(issue, dict) or not issue.get("title"):
            continue
        severity = issue.get("severity") if issue.get("severity") in SEVERITY_RANK else "low"
        confidence = issue.get("confidence")
        issues.append({
            "severity": severity,
            "title": str(issue.get("title")).strip(),
            "description": str(issue.get("description") or "").strip(),
            "recommendation": str(issue.get("recommendation") or "").strip(),
            "confidence": float(confidence) if isinstance(confidence, (int, float)) and 0 <= confidence <= 1 else 0.5,
        })
    expectation = raw.get("expectation") if raw.get("expectation") in {"met", "not_met", "unclear"} else "unclear"
    return {
        "screen_name": str(raw.get("screen_name") or "Écran").strip(),
        "summary": str(raw.get("summary") or "").strip(),
        "expectation": expectation,
        "expectation_notes": str(raw.get("expectation_notes") or "").strip(),
        "focus_visible": raw.get("focus_visible") if isinstance(raw.get("focus_visible"), bool) else None,
        "focused_element": str(raw.get("focused_element") or "").strip(),
        "visual_score": score("visual_score"),
        "usability_score": score("usability_score"),
        "issues": issues,
    }


def analyse_step(model: str, api_key: str, scenario: Scenario, step: Step, action: str,
                 current: bytes, before: bytes | None) -> dict[str, Any]:
    prompt = f"""{APP_CONTEXT}

Scénario : {scenario.title} (fonctionnalité « {scenario.area} »).
Action effectuée : {action}
Résultat attendu : {step.expect}

La PREMIÈRE image est l'écran APRÈS l'action{"; la SECONDE est l'écran AVANT" if before else ""}.
Vérifie si le résultat attendu est atteint, puis audite cet écran.

{AUDIT_SCHEMA}"""
    images = [current] + ([before] if before else [])
    return normalise_audit(gemini(model, api_key, prompt, images))


def choose_goal_key(model: str, api_key: str, scenario: Scenario, step: Step, current: bytes,
                    pressed: list[str]) -> dict[str, Any]:
    prompt = f"""{APP_CONTEXT}

Tu pilotes la télécommande pour atteindre cet état : {step.goal}
Touches déjà appuyées pour cet objectif : {", ".join(pressed) or "aucune"}.
Règles de sécurité absolues : n'appuie sur ENTER que si l'élément focalisé est exactement celui
qui atteint l'objectif ; jamais sur favori, « vu », suppression, déconnexion, réglage, code PIN,
lecture non demandée. En cas de doute, réponds DONE.

Retourne UNIQUEMENT ce JSON :
{{"goal_reached": false, "focused_element": "...", "next_key": "UP|DOWN|LEFT|RIGHT|ENTER|BACK|DONE",
  "reason": "pourquoi cette touche"}}"""
    raw = gemini(model, api_key, prompt, [current], max_tokens=400)
    key = raw.get("next_key") if raw.get("next_key") in {*KEY_EVENTS, "DONE"} else "DONE"
    return {"goal_reached": bool(raw.get("goal_reached")), "next_key": key,
            "reason": str(raw.get("reason") or ""), "focused_element": str(raw.get("focused_element") or "")}


# --------------------------------------------------------------------------- run

def describe(keys: list[Any]) -> str:
    if not keys:
        return "aucune (état initial)"
    return " ".join(f"saisie « {key[1]} »" if isinstance(key, tuple) else key for key in keys)


def press(device: Device, keys: list[Any]) -> None:
    for key in keys:
        if isinstance(key, tuple) and key[0] == "TEXT":
            device.text(key[1])
        else:
            device.key(key)
        time.sleep(0.25)


def start_app(device: Device, launch_timeout: float) -> tuple[bytes, float]:
    """Fresh start for each scenario: known state (Home, focus on Favoris)."""
    device.force_stop()
    time.sleep(1.0)
    device.wake()
    device.launch()
    # The intro video moves until Home: wait for a still screen.
    started = time.monotonic()
    time.sleep(3.0)
    png, _, _ = device.wait_settled(timeout=launch_timeout, interval=0.6)
    return png, time.monotonic() - started


def run_audit(args: argparse.Namespace, device: Device, report_dir: Path, api_key: str | None,
              data: dict[str, Any]) -> None:
    """Fills `data` as it goes: an interrupted audit still gets its report."""
    captures = report_dir / "captures"
    captures.mkdir(parents=True, exist_ok=True)
    wanted = set(args.only.split(",")) if args.only else None
    plan = [s for s in scenarios(args.allow_playback) if not wanted or s.id in wanted]
    results: list[dict[str, Any]] = data["scenarios"]
    counter = 0
    data["memory_start_mb"] = device.memory_mb()

    for scenario in plan:
        print(f"\n== {scenario.title}")
        device.clear_logs()
        device.reset_gfx()
        record: dict[str, Any] = {"id": scenario.id, "title": scenario.title, "area": scenario.area,
                                  "playback": scenario.playback, "steps": [], "crashes": [], "left_app": False}
        try:
            before, launch_seconds = start_app(device, args.launch_timeout)
            record["launch_seconds"] = round(launch_seconds, 1)
            for step in scenario.steps:
                actions: list[Any] = []
                goal_trace: list[str] = []
                if step.goal:
                    current = before
                    for _ in range(step.max_keys):
                        if not api_key:
                            break
                        decision = choose_goal_key(args.model, api_key, scenario, step, current,
                                                   [str(a) for a in actions])
                        goal_trace.append(f"{decision['next_key']} - {decision['reason']}")
                        if decision["goal_reached"] or decision["next_key"] == "DONE":
                            break
                        press(device, [decision["next_key"]])
                        actions.append(decision["next_key"])
                        current, _, _ = device.wait_settled(timeout=step.settle)
                else:
                    press(device, step.keys)
                    actions = list(step.keys)
                png, seconds, settled = device.wait_settled(timeout=step.settle)
                counter += 1
                name = f"{counter:03d}_{scenario.id}.png"
                (captures / name).write_bytes(png)
                foreground = device.in_foreground()
                action = describe(actions) + (f" (objectif : {step.goal})" if step.goal else "")
                entry: dict[str, Any] = {
                    "index": counter, "action": action, "expect": step.expect, "capture": f"captures/{name}",
                    "settle_seconds": round(seconds, 2), "settled": settled, "foreground": foreground,
                    "goal_trace": goal_trace,
                }
                if not foreground:
                    record["left_app"] = True
                if api_key:
                    try:
                        entry.update(analyse_step(args.model, api_key, scenario, step, action, png, before))
                    except AuditError as error:
                        entry["ai_error"] = str(error)
                status = {"met": "OK", "not_met": "ÉCHEC", "unclear": "?"}.get(entry.get("expectation", ""), "-")
                print(f"  [{status}] {step.expect[:90]}  ({seconds:.1f}s{'' if settled else ', écran animé'})")
                record["steps"].append(entry)
                before = png
                if not foreground:
                    print("  ! L'application n'est plus au premier plan : scénario interrompu.")
                    break
        except AuditError as error:
            record["error"] = str(error)
            print(f"  ! {error}")
        record["crashes"] = device.crash_logs()
        record["jank"] = device.jank()
        if record["crashes"]:
            print(f"  ! {len(record['crashes'])} plantage(s) détecté(s) dans les journaux")
        results.append(record)

    data["memory_end_mb"] = device.memory_mb()


# --------------------------------------------------------------------------- report

def similar(a: str, b: str) -> bool:
    words_a = set(re.findall(r"\w{4,}", a.lower()))
    words_b = set(re.findall(r"\w{4,}", b.lower()))
    if not words_a or not words_b:
        return a.lower() == b.lower()
    return len(words_a & words_b) / min(len(words_a), len(words_b)) >= 0.6


def collect_findings(data: dict[str, Any]) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []

    def add(severity: str, title: str, description: str, recommendation: str, where: str,
            capture: str | None, source: str, confidence: float = 1.0) -> None:
        for existing in findings:
            if similar(existing["title"], title):
                existing["where"].add(where)
                if SEVERITY_RANK[severity] < SEVERITY_RANK[existing["severity"]]:
                    existing["severity"] = severity
                if capture and len(existing["captures"]) < 4:
                    existing["captures"].append(capture)
                return
        findings.append({"severity": severity, "title": title, "description": description,
                         "recommendation": recommendation, "where": {where}, "captures": [capture] if capture else [],
                         "source": source, "confidence": confidence})

    for scenario in data["scenarios"]:
        for crash in scenario["crashes"]:
            add("critical", "Plantage de l'application", f"```\n{crash}\n```",
                "Corriger l'exception (voir la pile ci-dessus).", scenario["title"], None, "journaux")
        if scenario["left_app"]:
            add("critical", "L'application a quitté le premier plan",
                "Après une touche, l'application n'était plus affichée (plantage, fermeture ou autre appli).",
                "Vérifier la capture de l'étape et les journaux.", scenario["title"], None, "système")
        if scenario.get("error"):
            add("high", "Scénario interrompu par une erreur", scenario["error"], "Voir l'erreur.",
                scenario["title"], None, "outil")
        jank = scenario.get("jank") or {}
        if (jank.get("janky_percent") or 0) > 25 and (jank.get("frames") or 0) > 60:
            add("medium", "Animations saccadées",
                f"{jank['janky_percent']} % d'images en retard ({jank['janky']} / {jank['frames']}), "
                f"90e centile {jank.get('p90_ms')} ms.",
                "Profiler les recompositions / listes de cet écran.", scenario["title"], None, "gfxinfo")
        for step in scenario["steps"]:
            where = f"{scenario['title']} - étape {step['index']}"
            if step.get("expectation") == "not_met":
                add("high", f"Comportement inattendu : {step['expect'][:80]}",
                    f"Action : {step['action']}. {step.get('expectation_notes', '')}",
                    "Vérifier la navigation / le rendu de cette étape.", where, step["capture"], "IA")
            if step.get("focus_visible") is False and "lecteur" not in scenario["area"].lower():
                add("medium", "Focus D-pad invisible",
                    f"Aucun élément focalisé visible après « {step['action']} ».",
                    "Garder un focus visible en permanence à la télécommande.", where, step["capture"], "IA")
            if step["settled"] and step["settle_seconds"] > 3.0:
                add("low", "Écran lent à se stabiliser",
                    f"{step['settle_seconds']} s avant un écran stable après « {step['action']} ».",
                    "Afficher un état de chargement immédiat ou accélérer le chargement.", where,
                    step["capture"], "mesure")
            for issue in step.get("issues", []):
                add(issue["severity"], issue["title"], issue["description"], issue["recommendation"], where,
                    step["capture"], "IA", issue["confidence"])
    findings.sort(key=lambda item: (SEVERITY_RANK[item["severity"]], -item["confidence"]))
    return findings


def write_reports(report_dir: Path, meta: dict[str, Any], data: dict[str, Any]) -> tuple[Path, Path]:
    findings = collect_findings(data)
    steps = [step for scenario in data["scenarios"] for step in scenario["steps"]]
    judged = [step for step in steps if step.get("expectation") in {"met", "not_met"}]
    passed = sum(1 for step in judged if step["expectation"] == "met")
    visual = [step["visual_score"] for step in steps if step.get("visual_score")]
    usability = [step["usability_score"] for step in steps if step.get("usability_score")]
    counts = {severity: sum(1 for f in findings if f["severity"] == severity) for severity in SEVERITIES}

    json_path = report_dir / "report.json"
    json_path.write_text(json.dumps({**meta, **data, "findings": [
        {**finding, "where": sorted(finding["where"])} for finding in findings
    ]}, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")

    def avg(values: list[int]) -> str:
        return f"{sum(values) / len(values):.1f}/5" if values else "-"

    lines = [
        "# Audit fonctionnel et visuel de bTV (Fire TV)",
        "",
        f"- Généré : {meta['generated_at']} - appareil `{meta['device']}` - package `{meta['package']}` - "
        f"modèle `{meta['model'] or 'sans IA'}`",
        f"- Scénarios : {len(data['scenarios'])} - étapes : {len(steps)} - captures : {len(steps)}",
        f"- Attendus vérifiés : **{passed}/{len(judged)} conformes**"
        + (f" ({len(steps) - len(judged)} non tranchés)" if len(steps) != len(judged) else ""),
        f"- Qualité visuelle moyenne : **{avg(visual)}** - ergonomie : **{avg(usability)}**",
        f"- Constats : {counts['critical']} critique(s), {counts['high']} majeur(s), {counts['medium']} moyen(s), "
        f"{counts['low']} mineur(s), {counts['info']} info(s)",
        f"- Mémoire de l'appli : {data.get('memory_start_mb') or '?'} Mo au début, "
        f"{data.get('memory_end_mb') or '?'} Mo à la fin",
        "",
        "> Les captures sont prises quand l'écran est stable (plus aucune image ne change), jamais en plein "
        "milieu d'une transition. « Écran animé » signale un écran qui bouge encore (vidéo, chargement).",
        "",
        "## Vue d'ensemble par fonctionnalité",
        "",
        "| Fonctionnalité | Étapes conformes | Visuel | Ergonomie | Plantages | Saccades | Démarrage |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for scenario in data["scenarios"]:
        s_steps = scenario["steps"]
        s_judged = [s for s in s_steps if s.get("expectation") in {"met", "not_met"}]
        s_pass = sum(1 for s in s_judged if s["expectation"] == "met")
        jank = scenario.get("jank") or {}
        lines.append(
            f"| {scenario['title']} | {s_pass}/{len(s_judged)} | "
            f"{avg([s['visual_score'] for s in s_steps if s.get('visual_score')])} | "
            f"{avg([s['usability_score'] for s in s_steps if s.get('usability_score')])} | "
            f"{len(scenario['crashes'])} | "
            f"{(str(jank['janky_percent']) + ' %') if jank.get('janky_percent') is not None else '-'} | "
            f"{(str(scenario['launch_seconds']) + ' s') if scenario.get('launch_seconds') is not None else '-'} |"
        )

    lines += ["", "## Constats (du plus grave au moins grave)", ""]
    if not findings:
        lines.append("Aucun constat.")
    for number, finding in enumerate(findings, 1):
        lines += [
            f"### {number}. [{finding['severity'].upper()}] {finding['title']}",
            "",
            f"*Où :* {', '.join(sorted(finding['where']))} - *source :* {finding['source']}"
            + (f" - *confiance :* {finding['confidence']:.0%}" if finding["source"] == "IA" else ""),
            "",
            finding["description"],
            "",
            f"**Correction suggérée :** {finding['recommendation']}",
            "",
        ]
        for capture in finding["captures"][:2]:
            lines += [f"![capture]({capture})", ""]

    lines += ["## Détail des scénarios", ""]
    for scenario in data["scenarios"]:
        lines += [f"### {scenario['title']}", ""]
        if scenario.get("error"):
            lines += [f"Erreur : {scenario['error']}", ""]
        for step in scenario["steps"]:
            status = {"met": "OK", "not_met": "ÉCHEC", "unclear": "non tranché"}.get(step.get("expectation", ""), "-")
            lines += [
                f"**Étape {step['index']} - {status}** - action : {step['action']}",
                "",
                f"- Attendu : {step['expect']}",
                f"- Observé : {step.get('summary') or '-'}",
            ]
            if step.get("expectation_notes"):
                lines.append(f"- Vérification : {step['expectation_notes']}")
            if step.get("focused_element"):
                lines.append(f"- Focus : {step['focused_element']}")
            lines.append(f"- Écran stable en {step['settle_seconds']} s" + ("" if step["settled"] else " (encore animé)"))
            if step.get("goal_trace"):
                lines.append(f"- Pilotage IA : {' / '.join(step['goal_trace'])}")
            if step.get("ai_error"):
                lines.append(f"- Analyse IA indisponible : {step['ai_error']}")
            lines += ["", f"![étape {step['index']}]({step['capture']})", ""]

    lines += [
        "## Limites",
        "",
        "- Rien n'est modifié : ni favori, ni réglage, ni code PIN, ni compte. Les fonctions qui modifient "
        "(ajout aux favoris, marquer vu, changer un réglage) ne sont donc vérifiées que visuellement.",
        "- Le lecteur n'est audité qu'avec --allow-playback (un seul flux à la fois, arrêté à la fin).",
        "- La version téléphone (tactile, vertical, fenêtre flottante) n'est pas couverte : l'audit pilote une TV.",
        "- Les captures partent vers l'API Gemini et restent dans ce dossier.",
    ]
    md_path = report_dir / "report.md"
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return md_path, json_path


# --------------------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description="Audit fonctionnel et visuel de bTV sur Fire TV, scénario par scénario.")
    parser.add_argument("--device", help="Serial ADB ou adresse réseau (ex. 192.168.1.69:5555).")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help=f"Package Android (défaut : {DEFAULT_PACKAGE}).")
    parser.add_argument("--model", default=os.environ.get("GEMINI_MODEL", DEFAULT_MODEL), help="Modèle Gemini.")
    parser.add_argument("--allow-playback", action="store_true",
                        help="Ajoute les scénarios du lecteur (film puis chaîne, un flux à la fois).")
    parser.add_argument("--only", help="Scénarios à lancer, séparés par des virgules (ex. home,series,settings).")
    parser.add_argument("--list", action="store_true", help="Affiche les scénarios disponibles et quitte.")
    parser.add_argument("--no-ai", action="store_true",
                        help="Sans Gemini : captures, plantages, temps et saccades seulement.")
    parser.add_argument("--launch-timeout", type=float, default=30.0,
                        help="Attente maximale de l'accueil après lancement (intro comprise), en secondes.")
    parser.add_argument("--confirm-gemini-upload", action="store_true",
                        help="Confirme l'envoi des captures d'écran à l'API Gemini.")
    args = parser.parse_args()

    if args.list:
        for scenario in scenarios(True):
            print(f"{scenario.id:12} {scenario.title}{'  (--allow-playback)' if scenario.playback else ''}")
        return 0

    api_key = None
    if not args.no_ai:
        if not args.confirm_gemini_upload:
            parser.error("Les captures seront envoyées à Google Gemini : ajoute --confirm-gemini-upload "
                         "(ou --no-ai pour un audit sans IA).")
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            parser.error("GEMINI_API_KEY n'est pas définie (ou utilise --no-ai).")

    adb = find_adb()
    serial = select_device(adb, args.device)
    device = Device(adb, serial, args.package)
    report_dir = REPORT_ROOT / datetime.now().strftime("firetv-audit-%Y%m%d-%H%M%S")
    report_dir.mkdir(parents=True, exist_ok=False)
    meta = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "device": serial, "package": args.package, "model": None if args.no_ai else args.model,
        "allow_playback": args.allow_playback,
    }
    print(f"Appareil : {serial} - rapport : {report_dir}")
    data: dict[str, Any] = {"scenarios": []}
    try:
        run_audit(args, device, report_dir, api_key, data)
    except KeyboardInterrupt:
        print("\nInterrompu : rapport partiel.")
    finally:
        if args.allow_playback:
            device.force_stop()  # never leave a stream open (the IPTV account limits connections)
        md_path, json_path = write_reports(report_dir, meta, data)
        print(f"\nRapport : {md_path}\nDonnées : {json_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AuditError as error:
        print(f"Erreur d'audit : {error}", file=sys.stderr)
        raise SystemExit(1)
