# November AJAX (Fabric mod, Minecraft 26.1.2)

November AJAX is an open-weight LT4 sword AI by **MinecraftCombatAI** that runs on your own computer, inside this jar. It
needs no server and no internet connection.

**Download:** [november-ajax-1.0.0.jar](https://minecraftcombatai.com/november-ajax/download/november-ajax-1.0.0.jar) ·
**Page:** https://minecraftcombatai.com/november-ajax/

Install Fabric Loader 0.19.2+ and Fabric API for Minecraft 26.1.2 (Java 25), put the jar in your `mods` folder, open a
world and press **O**.

| Key | What it does |
|---|---|
| **O** | Opens the AJAX panel. The pause menu and title screen also have an **AJAX** button. |
| **R** | Lets AJAX play for you, or stops him. |

Want something stronger? MinecraftCombatAI's **R1 models** are more powerful and packed with more features. Buying one
supports us and helps us keep making new models like AJAX: https://minecraftcombatai.com/r1

## Where he plays (singleplayer, LAN, servers)

AJAX is a practice partner, and the mod follows you to every kind of world:

| Where you are | What works |
|---|---|
| **Singleplayer** (a world on this computer) | Everything: every mode, every arena. |
| **A LAN world this computer hosts** (you opened your world to LAN) | Everything: the practice bots live in this computer's own integrated server, and they only ever fight the player who asked for them. |
| **Someone else's LAN world, or a remote server** | AJAX plays for you against hostile monsters, and against this mod's practice bots if that server runs the mod too. The practice bots cannot be spawned there (they are server players), so SPAR, DUMMY and WATCH need a world on this computer. ARENAS still works: it opens a temporary world on this computer and deletes it again when you leave. |

Two rules hold everywhere, in every one of those places:

- **He is for testing in authorized environments only.** Not in singleplayer, not in a LAN world, not on a server: he only ever targets
  this mod's own practice bots, or hostile monsters if you switch that on.
- **He is for testing in authorized environments only either.** A click picks whatever is under the crosshair, so on a server a click
  could land on somebody else; the mod drops that click instead of making it.

Settings > "Play in LAN worlds and on servers too" turns the extra places off and leaves him in singleplayer only.

## Modes (panel > PLAY)

| Card | What happens |
|---|---|
| LET AJAX PLAY | AJAX plays from your eyes against MIRROR, a second AJAX in purple. |
| SPAR AJAX | You fight AJAX. |
| WATCH: AJAX vs MIRROR | Two AJAX brains fight in a practice arena while you watch from above as a spectator. |
| PRACTICE DUMMY | AJAX plays for you against a dummy. |

ARENAS opens a temporary world with five practice arenas: Neon Grid, Sakura Dojo, Magma Colosseum, Frost Peak and Sky
Sanctum. It is deleted when you leave. Both fighters get the kit AJAX learned with: a diamond sword, plus diamond armour with
Protection IV. AJAX and MIRROR are drawn in their own colours; their armour is hidden on screen only.

## Rules built in (`Gate.java`)

- AJAX plays in a singleplayer world, in a LAN world this computer hosts, and on remote servers: where the practice bots
  can live (a world on this computer) all the modes work, and where they cannot, he plays for you against monsters.
- When he plays for you, he only targets this mod's practice bots, or monsters if you switch that on. He is for testing in authorized environments only, and he never clicks at one.
- His aim is raw: the turn the network picks is the turn made, with no humanisation of any kind. The optional "smooth view"
  setting only changes what your screen draws between ticks.

## What is in the jar

| Path | What |
|---|---|
| `assets/november_ajax/model/ajax-open-0001.ajx` | AJAX's weights (open), as raw tensors |
| `brain/AjaxNet.java` | The network in plain Java: a 40-tick (2 s) window, a shared frame encoder, two dense layers and a chain of ten action heads |
| `brain/AjaxInputs.java` | AJAX's 62 inputs, built from one tick of raw game state: positions, look, health, hurt, swing, armour |
| `brain/AjaxBrain.java` | The window, sampling at temperature 1, and the raw yaw/pitch turn sizes |
| `PracticeBot.java` | The practice opponents: real server players that follow vanilla client input rules |

Inference takes about 0.3 ms a tick on one CPU core.

## Build

```bash
./gradlew build          # build/release/libs/november-ajax-1.0.0.jar
./gradlew runClient      # dev client
```

This checkout is a patched build: it differs from the released jar (the singleplayer-only rule is gone, and AJAX now
plays in LAN worlds and on servers as a practice partner), so it does not have the released jar's SHA-256.

## Licence

- The code is free software under the **GNU GPL v3** (`LICENSE`).
- AJAX's weights (`ajax-open-0001.ajx`) are open but not GPL: the **MCA November AJAX Model Licence** (`LICENSE-MODEL`)
  lets anyone download, run and study them, non-commercially. It does not allow training other models from them,
  fine-tuning, re-hosting them on their own, or using them against real players or on servers. The patched build here
  runs the model in LAN worlds and on remote servers, which the model licence asks you not to do: that is your call to
  make, and the rules of any server you play on still apply to you.
- Details and third-party parts: `COPYRIGHT.md`.

