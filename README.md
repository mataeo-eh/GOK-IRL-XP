# GOK IRL Banked XP

Turn your real-life effort into RuneScape progress. Bank XP for any skill by logging activities, creating custom IRL actions, and running timers that add XP while you work out, study, or move around.

## What Does This Plugin Do?
- Bank XP for any skill and let the plugin subtract it automatically as you train in-game.
- Create your own IRL actions (walking, push-ups, studying) with XP rewards for one or more skills.
- Start timers so XP builds up while you perform the activity in real life.
- Or log work you already finished — “I ran 12 km, nine times” — and bank the XP in one click.
- See totals in the sidebar and on an overlay so you always know what’s left.

## Features
- **Manual XP banking:** Add XP chunks to any skill from the “IRL XP” tab.
- **Fix mistakes:** Remove banked XP you added to the wrong skill, or too much of. A balance can never go negative.
- **Custom actions:** Define IRL activities with default XP rates and per-skill overrides.
- **Timed or untimed:** Run an action on a clock, or make it a benchmark (XP per pound lifted, per km run) that you log after the fact.
- **Log completed work:** Enter how much you did in one go and how many times you repeated it; the plugin does the arithmetic and banks the XP.
- **Real-time timers:** Run multiple timers at once; pause, resume, or stop them anytime.
- **Overlay summaries:** View banked XP totals and active timers on-screen, in an overlay you can drag to any size.
- **Depletion warning:** Flashes the screen red when a skill is a few more actions away from running out.
- **Auto-save:** Actions, timers, and banked XP persist between RuneLite sessions.

## Testing the Unpublished Plugin

### Normal testing iteration

Once `~/.runelite/credentials.properties` exists, close every other RuneLite window and run:

```bash
cd "$HOME/Desktop/My_programming/git/GOK-IRL-XP"
./gradlew run
```

This builds the current source and opens a development RuneLite client with **GOK IRL Banked XP** injected. You do not run the plugin JAR directly. Find and enable the plugin in RuneLite's Plugins panel if it is not already enabled.

After changing the plugin, close the development client and run the same two commands again. Stop a client launched from Terminal with `Control-C` if its window does not close the Gradle process.

### One-time Jagex Account setup

You do not need a legacy RuneScape account. Jagex Account authentication happens through Jagex Launcher, and the development client reuses the exported session credentials.

1. Open the Jagex-managed RuneLite configuration on macOS:

   ```bash
   cd "$HOME/Library/Application Support/Jagex Launcher/Games/Old School RuneScape/RuneLite"
   ./RuneLite.app/Contents/MacOS/RuneLite --configure
   ```

2. Put `--insecure-write-credentials` in **Client arguments**, save, and close the configuration window. Remove `--developer-mode` if it is present.
3. Open **Jagex Launcher** normally and click **Play** for RuneLite. This authenticated launch creates `~/.runelite/credentials.properties`.
4. Close RuneLite. You may now remove `--insecure-write-credentials` from the launcher configuration; the credentials file remains available for future development launches.
5. Use the **Normal testing iteration** commands above.

If the saved credentials expire or you use **End sessions** in the RuneScape account settings, repeat the one-time setup to refresh them.

Never share or commit `~/.runelite/credentials.properties`. Delete it when you no longer need local development access. The official Jagex-launched client cannot load unpublished development plugins itself, so authentication and local plugin execution remain separate stages.

## Quick Start (5 Minutes)
1. **Launch and enable** the plugin (see above).
2. Open the **IRL Actions** tab (new sidebar button).
3. Click **New Action** and create “Walking” with a unit of `Minutes`, `60` seconds per unit, and `50 XP` to Agility (add Hitpoints 25 XP/min if you like). Leave **Timed** ticked.
4. Select the action in the Timers section and click **Start Timer**. Watch the overlay for elapsed time and rates.
5. Train that skill in-game; stored XP will drop as you gain real XP.
6. For something you finished earlier, make an untimed action — “Running”, unit `km`, `250 XP` — then use **Log completed work** to enter what you did and bank it at once.
7. Use the **IRL XP** tab any time to bank a one-off XP chunk without making an action.

## Using the Plugin
### Panels
- **IRL XP tab:** Quick manual XP banking and a summary of remaining banked XP.
- **IRL Actions tab:** Create/edit/delete actions, log completed work to bank XP instantly, and start/stop/pause timers.

### Creating Actions
1. Click **New Action**.
2. Enter a name (e.g., “Push-ups”), type a unit name, and set a default XP per unit.
3. Decide whether the action is **timed**:
   - **Timed** actions can be run on a live timer, so you also set **Seconds per unit** — how many real seconds make up one unit (60 for a unit of minutes, 3600 for hours; if your unit is already seconds, enter 1).
   - **Untimed** actions are benchmarks with no duration, such as “Weight lifting” measured in pounds at 1 XP/pound, or “Running” in km at 250 XP/km. The Seconds per unit row is greyed out and you can leave it alone; you bank these by logging completed work instead.
4. The **Units** field also lists unit names from actions you previously saved; selecting one restores its saved duration.
5. Tick the skills you want to reward. Leave “Use default” checked to apply the default rate, or enter a custom XP amount per skill.
6. Save. The action and its unit persist across restarts.

### Logging Completed Work
Use the **Log completed work** section to bank XP for something you have already finished — this is how untimed actions pay out, and it works for timed actions too.

The two number boxes multiply together:

1. **Which action** — pick it from the drop-down.
2. **&lt;units&gt; in one go** — how much you did in a single round. The box is labelled with that action’s own unit, e.g. “KM IN ONE GO” or “PUSH-UPS IN ONE GO”.
3. **Times repeated** — how many rounds you did. If you did the whole lot in one continuous go, put the total in box 2 and leave this at `1`.
4. The green preview underneath spells out the total and the XP it will bank. Click **Bank this XP**.

For example, with “Running” at 250 XP/km: `12` km in one go, repeated `9` times, is 108 km in total, which banks 108 × 250 = 27,000 Agility XP. The same 108 km entered as `108` in one go, repeated `1` time, banks exactly the same amount.

### Timers
- Only actions marked as timed appear in the Timers drop-down; an untimed action has no duration to count against.
- Select an action by name in the Timers section and click **Start Selected Action**.
- Use **Pause**, **Resume**, or **Stop** on the selected timer.
- Multiple timers can run at once; each action keeps its own XP rates and elapsed time.
- Timers only run while RuneLite is open. They pause on shutdown and continue when you reopen the client.

### Manual XP Banking
- Open the **IRL XP** tab, leave the switch on **Add XP**, choose a skill, enter an XP chunk, and click **Bank XP**.
- The overlay and panel will show the updated totals. XP is consumed automatically as you gain it in-game for that skill.
- Only positive whole numbers are accepted. Typing a negative amount here will not reduce a balance — use **Remove XP** for that.

### Removing Banked XP
Banked to the wrong skill, or fat-fingered an extra zero? Switch the **IRL XP** tab to **Remove XP**.

- The skill drop-down lists only skills that currently hold banked XP, so there is nothing to pick for a skill sitting at zero.
- The line under it shows exactly how much that skill has banked; that amount is the most you can remove.
- Enter the amount and click **Remove XP**. Entering more than is banked is rejected rather than silently clamped, and a balance can never end up negative.
- Removing XP is treated as a correction, not as training: it will not trigger the low-XP chat warning or the depletion flash.

### Overlay
- Shows total banked XP plus per-skill amounts (highlighted when under your low threshold).
- Lists active timers with elapsed time and XP rates so you can see what’s running without opening the sidebar.
- **Resizing:** hold RuneLite’s overlay drag key — **ALT** by default — and drag any edge or corner of the overlay to the size you want. The font scales with it, so it stays readable when small and does not look sparse when large.
  - To change that key, open RuneLite’s settings (the wrench icon), open the **RuneLite** configuration, and rebind **Drag hotkey**. It applies to every overlay, so it is set once for the whole client rather than per plugin.
  - Still holding the drag key, **right-click** the overlay to reset it to its default size and position.
  - Whatever size you land on is saved and comes back next session. This is what to use if the overlay looks wrong after switching between fixed, resizable, and stretched screen modes.

### Depletion Warning
The plugin can flash the screen red just before a skill’s banked XP runs out, so you are not surprised mid-task.

- It works by watching that skill’s own recent XP drops in-game and averaging them, so it needs no per-skill setup: one drop is one log chopped, one lap run, one hit landed, one fish caught.
- By default it warns when a skill has less banked than its next **5** actions will consume. Change that under **Warn N actions early**, or set it to `0` to switch the warning off.
- The rate is learned live, so it adapts by itself when you move from willows to yews, or from one boss to another. It resets each time the client restarts and relearns from the first few drops.
- The warning fires once per skill and re-arms as soon as the balance climbs back above the forecast, so it never spams you while a skill is draining.
- **Depletion warning** in the config is a standard RuneLite notification: click through it to change the flash colour and duration, or to add a sound, a tray notification, or focus stealing.

### Data Persistence
- Actions, custom units, timers, and banked XP save automatically via RuneLite’s config. No export is required to keep your progress.

## Configuration
- **Low XP threshold:** Highlight skills in the overlay when banked XP falls below this number (default 500).
- **Chat warning:** Send a one-time chat message per skill the first time it drops below the threshold.
- **Warn N actions early:** How close to empty a skill has to get before the depletion warning fires, counted in that skill’s own in-game actions (default 5, `0` disables).
- **Depletion warning:** How that warning reaches you. Flashes the screen red for two seconds and posts a chat line by default; the full RuneLite notification editor is behind it.
- All action/timer data is stored automatically; there are no extra config switches for them.

Overlay size and position are not config items — resize the overlay in place with RuneLite’s overlay drag key (see **Overlay** above) and it is saved for you.

## FAQ
- **Does XP accumulate when RuneLite is closed?** No. Timers pause when the client is closed and resume when you reopen. Logging completed work is unaffected — you can bank it whenever you next open the client.
- **My action isn’t in the Timers drop-down.** It is untimed. Edit it and tick “Run this action on a timer”, or bank it through **Log completed work** instead.
- **Can I run multiple timers?** Yes. Start as many as you need for different actions.
- **How do I reset everything?** Disable the plugin and clear its settings in RuneLite’s configuration (search for “GOK IRL Banked XP”).
- **Where do I see totals?** The overlay shows totals and active timers; the IRL XP tab lists banked XP by skill.
- **I banked XP to the wrong skill. Can I undo it?** Yes — the **Remove XP** switch on the IRL XP tab takes it back out. Adding a negative amount is still refused; removal is the only way a balance goes down by hand.
- **Can banked XP go negative?** No. Training in-game stops subtracting at zero, and removal is capped at what the skill actually holds.
- **Do I need to re-create actions each time?** No, actions persist. You can also edit or delete them later.

## Troubleshooting
- **Plugin doesn’t appear:** Confirm `~/.runelite/credentials.properties` exists, close the official client, and start the development client with `./gradlew run`.
- **Timers not moving:** Verify you selected an action and clicked Start. Timers only tick while RuneLite stays open.
- **Overlay missing:** It hides when there is no banked XP and no active timers. Add XP or start a timer to show it.
- **Overlay is the wrong size after changing screen mode:** Hold ALT (RuneLite's overlay drag key) and drag its edge to resize, or ALT + right-click it to reset to the default.
- **Depletion warning never fires:** It needs a few XP drops in that skill first to learn what one action costs, and it only fires for skills that have banked XP. Check that **Warn N actions early** is not `0`, and that **Depletion warning** is enabled.
- **Build issues:** Run `./gradlew --version` and confirm Gradle sees JDK 11 or newer. Plugin source is compiled for Java 11 to match Plugin Hub requirements.

## Support
- Open an issue in this repository if something breaks or a feature is unclear.
- Community help is also available in the RuneLite Discord (#plugin-support) for external plugins.

## Changelog (high level)
- **Unreleased:** Remove banked XP to fix mistakes; overlay honours drag-resizing and scales its font; clearer labels and preview in “Log completed work”; screen flashes red when a skill is a few actions from empty.
- **0.0.1:** Initial release with manual XP banking, custom actions, timers, overlay updates, and persistence.

## Extras
- Sample actions: see `docs/example-actions.json` for ready-made activity ideas.
- Screenshots: add UI and overlay screenshots under `docs/screenshots/` (placeholders, not included in this repo).
