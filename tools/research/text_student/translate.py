"""English music-search phrases translated by DeepSeek into 20 languages, as people would type them.

    LLM_KEY=... python3 translate.py   # writes translations.json
"""
import asyncio, json, os, random
import aiohttp

random.seed(2)
GENRES = ["rock", "pop", "hip hop", "rap", "jazz", "blues", "classical music", "electronic music", "dance music", "house",
          "techno", "trance", "metal", "punk", "folk", "country", "reggae", "reggaeton", "latin pop", "salsa", "bachata",
          "k-pop", "j-pop", "anime songs", "soundtracks", "movie music", "video game music", "chanson", "soul", "r&b",
          "funk", "disco", "indie", "alternative rock", "lo-fi", "ambient", "opera", "gospel", "children's songs",
          "traditional folk music", "bollywood songs", "afrobeats", "samba", "bossa nova", "flamenco", "tango",
          "drill", "trap", "phonk", "acoustic songs", "piano music", "orchestral music", "love songs", "christmas songs"]
MOODS = ["sad", "happy", "calm", "relaxing", "energetic", "romantic", "dark", "epic", "melancholic", "cheerful",
         "aggressive", "chill", "motivational", "nostalgic", "dreamy"]
USES = ["workout music", "music for sleep", "music for studying", "party music", "driving music", "music for a rainy day",
        "summer hits", "wedding songs", "music for running", "background music for work"]
LANGS = ["spanish", "portuguese", "french", "german", "italian", "turkish", "polish", "ukrainian", "russian", "romanian",
         "japanese", "korean", "chinese", "hindi", "arabic", "indonesian", "thai", "vietnamese", "english", "persian"]
phrases = set()
for g in GENRES:
    phrases.update([g, f"{g} songs" if not g.endswith(("music", "songs")) else g, f"best {g}", f"old {g}"])
for m in MOODS:
    phrases.update([f"{m} songs", f"{m} music"])
phrases.update(USES)
for d in ("60s", "70s", "80s", "90s", "2000s", "2010s"):
    phrases.update([f"{d} music", f"{d} hits", f"{d} rock", f"{d} pop"])
for l in LANGS:
    phrases.update([f"{l} songs", f"{l} music", f"{l} pop", f"{l} rap"])
for _ in range(60):
    phrases.add(f"{random.choice(MOODS)} {random.choice(GENRES[:30])}")
phrases = sorted(phrases)
TARGETS = {"es": "Spanish", "pt": "Portuguese (Brazil)", "fr": "French", "de": "German", "it": "Italian", "tr": "Turkish",
           "pl": "Polish", "uk": "Ukrainian", "ro": "Romanian", "ja": "Japanese", "ko": "Korean", "zh": "Chinese (Simplified)",
           "hi": "Hindi", "ar": "Arabic", "id": "Indonesian", "th": "Thai", "vi": "Vietnamese", "tl": "Filipino (Tagalog)",
           "fa": "Persian", "nl": "Dutch"}
SYSTEM = ("You translate short music-app search queries. For each English query, give the query a native {lang} speaker "
          "would actually type into a music app's search box to find the same music: natural, short, no quotes, keep "
          "common loanwords (k-pop, lo-fi, hip hop) the way locals write them. Answer in json: an object mapping every "
          "given English query to its {lang} query.")


async def one(session, code, lang, chunk):
    body = {"model": "deepseek-flash", "thinking": {"type": "disabled"}, "temperature": 0.2, "max_tokens": 8000,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": SYSTEM.format(lang=lang)},
                         {"role": "user", "content": json.dumps(chunk, ensure_ascii=False)}]}
    for attempt in range(4):
        try:
            async with session.post("https://api.deepseek.com/chat/completions", json=body,
                                    headers={"Authorization": f"Bearer {os.environ['LLM_KEY']}"},
                                    timeout=aiohttp.ClientTimeout(total=300)) as r:
                j = await r.json()
                return code, json.loads(j["choices"][0]["message"]["content"])
        except Exception:
            await asyncio.sleep(3 + 5 * attempt)
    return code, {}


async def main():
    chunks = [phrases[i:i + 120] for i in range(0, len(phrases), 120)]
    async with aiohttp.ClientSession() as s:
        res = await asyncio.gather(*(one(s, c, l, ch) for c, l in TARGETS.items() for ch in chunks))
    out = {}
    for code, m in res:
        out.setdefault(code, {}).update({k: v for k, v in m.items() if k in phrases and isinstance(v, str) and v.strip()})
    json.dump({"phrases": phrases, "translations": out}, open("translations.json", "w"), ensure_ascii=False, indent=1)
    print(len(phrases), "phrases;", {c: len(v) for c, v in out.items()})

asyncio.run(main())
