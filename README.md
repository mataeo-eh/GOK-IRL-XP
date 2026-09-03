# GOK IRL Banked XP

Turn your real-life effort into RuneScape progress. Bank XP for any skill by logging activities, creating custom IRL actions, and running timers that add XP while you work out, study, or move around.

## What Does This Plugin Do?
- Bank XP for any skill and let the plugin subtract it automatically as you train in-game.
- Create your own IRL actions (walking, push-ups, studying) with XP rewards for one or more skills.
- Start timers so XP builds up while you perform the activity in real life.
- Or log work you already finished — “I ran 12 km, nine times” — and bank the XP in one click.
- See totals in the sidebar and on an overlay so you always know what’s left.

## Features
- **Manual XP banking:** Add XP chunks to any skill from the “Banked XP” tab.
- **Fix mistakes:** Remove banked XP you added to the wrong skill, or too much of. Removing can never push a balance below zero.
- **XP debt:** Gain more XP in-game than you have banked for that skill — a quest reward, a lamp, training with nothing banked — and the balance goes negative. The next XP you bank for it pays the debt off before anything counts as banked again.
- **Custom actions:** Define IRL activities with default XP rates and per-skill overrides.
- **Timed or untimed:** Run an action on a clock, or make it a benchmark (XP per pound lifted, per km run) that you log after the fact.
- **Log completed work:** Enter how much you did in one go and how many times you repeated it; the plugin does the arithmetic and banks the XP.
- **Real-time timers:** Run multiple timers at once; pause, resume, or stop them anytime.
- **Level multipliers:** Set your own level thresholds per skill so banked XP scales as you level. You choose how many thresholds, what level each starts at, and what it multiplies by.
- **Overlay summaries:** View banked XP totals and active timers on-screen, in an overlay you can drag to any size.
- **Depletion warning:** Flashes the screen red when a skill is a few more actions away from running out.
- **Auto-save:** Actions, timers, thresholds, and banked XP persist between RuneLite sessions.

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
2. Open the sidebar's **ACTIONS** tab.
3. Click **New Action** and create “Walking” with a unit of `Minutes`, `60` seconds per unit, and `50 XP` to Agility (add Hitpoints 25 XP/min if you like). Leave **Timed** ticked.
4. Select the action in the Timers section and click **Start Timer**. Watch the overlay for elapsed time and rates.
5. Train that skill in-game; stored XP will drop as you gain real XP.
6. For something you finished earlier, make an untimed action — “Running”, unit `km`, `250 XP` — then use **Log completed work** to enter what you did and bank it at once.
7. Use the **BANKED XP** tab any time to bank a one-off XP chunk without making an action.
8. Optional: open the **LEVEL MULTIPLIERS** tab, pick a skill, and give it a threshold — say `2×` from level 70 — so your real-world effort is worth more as you level.

## Using the Plugin
### Panels
Every tab is a button under the **IRL XP** title in the sidebar, and the line under the title always says what the tab you are looking at is for.

- **Banked XP tab:** Quick manual XP banking, a summary of remaining banked XP, and a pointer to the warning settings.
- **Actions tab:** Create/edit/delete actions, log completed work to bank XP instantly, and start/stop/pause timers.
- **Level multipliers tab:** Set per-skill thresholds so banked XP scales with your in-game levels.

### Where every setting lives
| What you want to change | Where it is |
|---|---|
| Actions, units, XP rates | Sidebar → **ACTIONS** tab → **Action library** |
| Timers | Sidebar → **ACTIONS** tab → Timers section |
| Bank or remove XP by hand | Sidebar → **BANKED XP** tab |
| Level multipliers, per skill | Sidebar → **LEVEL MULTIPLIERS** tab |
| Low-XP chat warning | RuneLite Configuration (wrench) → **IRL XP** → *Low banked XP warning (chat)* |
| Red screen flash as a skill runs out | RuneLite Configuration (wrench) → **IRL XP** → *Almost-out warning (red screen flash)* |
| Turn all level multipliers on/off | RuneLite Configuration (wrench) → **IRL XP** → *Level multipliers* |
| Overlay size and position | Drag it in-game with RuneLite's overlay drag key (**ALT** by default) |

The RuneLite settings panel groups the plugin's options into three named sections, and each section says in plain language what the whole group controls. The **BANKED XP** tab carries a permanent note naming that route, because a plugin cannot open RuneLite's own settings panel for you.

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
- Open the **BANKED XP** tab, leave the switch on **Add XP**, choose a skill, enter an XP chunk, and click **Bank XP**.
- The overlay and panel will show the updated totals. XP is consumed automatically as you gain it in-game for that skill. If the skill is in debt (see **XP Debt** below), the deposit pays the debt down first.
- Only positive whole numbers are accepted. Typing a negative amount here will not reduce a balance — use **Remove XP** for that.

### Removing Banked XP
Banked to the wrong skill, or fat-fingered an extra zero? Switch the **BANKED XP** tab to **Remove XP**.

- The skill drop-down lists only skills that currently hold banked XP, so there is nothing to pick for a skill sitting at zero.
- The line under it shows exactly how much that skill has banked; that amount is the most you can remove.
- Enter the amount and click **Remove XP**. Entering more than is banked is rejected rather than silently clamped, and removing can never push a balance below zero. A skill that is in debt is not offered at all, because there is nothing banked to take back.
- Removing XP is treated as a correction, not as training: it will not trigger the low-XP chat warning or the depletion flash.

### XP Debt
Banked XP drains one for one as you gain XP in-game — and the game does not stop at zero, so neither does the plugin. When you gain more XP in a skill than you have banked for it, the balance keeps going down into a **debt**.

- With no Woodcutting XP banked, a quest that rewards 5,000 Woodcutting XP leaves the skill at **-5,000 XP**. Training 500 XP past a 300 XP balance leaves it at -200.
- The next XP you bank for that skill pays the debt off first. Banking 3,000 against a 5,000 debt leaves 2,000 owed; only once the debt is cleared does the balance climb back above zero and start counting as banked again.
- A debt can only come from in-game XP. **Bank XP** never creates one and **Remove XP** never deepens one — a skill in debt does not even appear in the removal drop-down.
- The sidebar marks a skill in debt **OWED** and the overlay shows it in red. The total at the top is the net figure: banked XP minus everything owed.
- Neither warning fires for a debt. The low-XP chat line and the red flash are about a balance that is about to run out, not one that already has.

This is what keeps the bank honest: every XP your character gains is matched by real-world effort sooner or later, whether you banked it beforehand or owe it afterwards.

### Level Multipliers
Bank more — or less — XP as your in-game levels climb. Each skill has its own ladder of thresholds, set on the **LEVEL MULTIPLIERS** tab, and skills you never touch are unaffected.

1. Open the **LEVEL MULTIPLIERS** tab and pick a skill. The line underneath shows that skill's level and what it is banking at right now.
2. Click **Set thresholds**.
3. At the top, choose **how many thresholds you want** for this skill. The rows below appear and disappear to match. `0` means no multiplier for that skill.
4. For each threshold, set the **level it starts at** (1–126, virtual levels included) and the **multiplier** to bank at.
5. The **What this means** panel restates your ladder as level bands as you type, and shows what 1,000 XP would bank at your current level. Save when it reads the way you want.

**Thresholds never stack.** Only the highest one you have reached applies, and reaching it replaces the one below it completely — a 1.5× at 50, a 2× at 70 and a 3× at 90 is 3× at level 90, not 9×. Below your lowest threshold, XP is banked exactly as earned.

The multiplier is any non-negative number. `2` doubles, `0.5` halves, `1` is no change, and `0` means that skill banks nothing at that level. A positive multiplier never rounds a deposit away to nothing — the smallest it can bank is 1 XP.

It applies to every deposit: timers, logged completed work, and the manual **Bank XP** form. It does **not** apply to **Remove XP**, which always takes back the literal figure you type, and it does not change how fast in-game XP drains your balance — that is always one for one.

- The **Bank XP** form shows what your typed amount will become before you press the button, and the **Log completed work** preview shows what the figures were before the multiplier applied.
- **Skills with multipliers** at the bottom of the tab lists every skill you have set up, so you can see the whole picture without clicking through twenty-three skills.
- Levels are remembered between sessions, so banking a run before you log in still uses your real level rather than treating you as level 1.
- To switch the whole feature off without losing your thresholds, untick **Use level multipliers** in RuneLite's settings. The tab says so in red while it is off.

### Overlay
- Shows total banked XP plus per-skill amounts (orange when under your low threshold, red when the skill is in debt). The total is the net figure: banked XP minus anything owed.
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
- Actions, custom units, timers, and banked XP save automatically. No export is required to keep your progress.
- Banked XP is saved in two places every time it changes: RuneLite’s config for the active profile, and a file of the plugin’s own at `~/.runelite/gok-irl-xp/banked-xp-<profile id>.json`. On start-up the plugin keeps whichever copy is newer and brings the other up to date.
- The file exists because RuneLite’s config alone is not safe for this kind of data. When a profile is synced to a RuneLite account and one upload fails, RuneLite replaces the local config with the server’s copy on the next launch, and everything banked since the last successful upload is rolled back. The plugin’s own file is never touched by that sync, so your balances survive it.
- Actions, units, timers and level multipliers still live only in RuneLite’s config.

## Configuration
Open RuneLite's Configuration panel (the wrench) and pick **IRL XP**. The options are grouped into three named sections.

**Low banked XP warning (chat)** — the quiet warning.
- **Send the chat warning:** Turn the chat message off entirely. It is sent once per skill and only becomes possible again after that skill is topped up.
- **Warn below this much XP:** The figure that counts as low (default 500). A skill at or under it is marked LOW in the sidebar and on the overlay.

**Almost-out warning (red screen flash)** — the loud one.
- **Warn me this many actions early:** How much notice you get, counted in that skill's own in-game actions (default 5). Set it to `0` to switch the whole almost-out warning off.
- **How you are warned:** The full RuneLite notification editor. Flashes the screen red for two seconds and posts a chat line by default; open it to change the flash colour, add a sound or tray popup, or turn the flash off while keeping the chat line.

**Level multipliers**
- **Use level multipliers:** Turns every skill's thresholds on or off at once. Off keeps your thresholds and banks XP unchanged. The thresholds themselves are set per skill on the sidebar's **LEVEL MULTIPLIERS** tab.

All action, timer, threshold and banked-XP data is stored automatically; there are no extra config switches for them.

Overlay size and position are not config items — resize the overlay in place with RuneLite’s overlay drag key (see **Overlay** above) and it is saved for you.

## FAQ
- **Does XP accumulate when RuneLite is closed?** No. Timers pause when the client is closed and resume when you reopen. Logging completed work is unaffected — you can bank it whenever you next open the client.
- **My action isn’t in the Timers drop-down.** It is untimed. Edit it and tick “Run this action on a timer”, or bank it through **Log completed work** instead.
- **Can I run multiple timers?** Yes. Start as many as you need for different actions.
- **How do I reset everything?** Banked XP: use **Remove XP** on the BANKED XP tab for each skill, or close RuneLite and delete `~/.runelite/gok-irl-xp/` along with the plugin’s settings in RuneLite’s configuration. Resetting only the RuneLite settings is not enough, because the plugin restores balances from its own file. Actions, units, timers and multipliers: clear the plugin’s settings in RuneLite’s configuration.
- **My banked XP disappeared after restarting RuneLite.** Versions before this one kept banked XP only in RuneLite’s config, which RuneLite’s profile sync can roll back to an older copy. The plugin now also keeps its own file and restores from it. Balances lost before this version cannot be recovered; re-bank them, and they will stay.
- **Every restart took exactly my skill’s total XP off the bank, and the almost-out warning fired the moment I logged in.** Earlier versions read your skill XP the instant the client reported you logged in, which on a freshly started client is before the game has sent your stats, so every skill read as 0 XP and your real totals then looked like XP you had just earned. The plugin now waits for the login to finish before taking that reading, the same way RuneLite’s own XP Tracker does. Balances lost this way cannot be recovered; re-bank them.
- **Where do I see totals?** The overlay shows totals and active timers; the BANKED XP tab lists banked XP by skill.
- **I banked XP to the wrong skill. Can I undo it?** Yes — the **Remove XP** switch on the BANKED XP tab takes it back out. Adding a negative amount is still refused; removal is the only way a balance goes down by hand.
- **Can banked XP go negative?** Only by gaining XP in-game. If you earn more in a skill than you have banked for it, the balance goes into debt and the next XP you bank pays that debt off first. See **XP Debt** above. **Bank XP** never creates a debt and **Remove XP** is capped at what the skill actually holds.
- **My skill shows a negative number / OWED. What happened?** You gained more XP in that skill than you had banked — often a quest reward or a lamp. Bank XP for that skill and the debt shrinks; once it reaches zero the balance starts building up again.
- **Do I need to re-create actions each time?** No, actions persist. You can also edit or delete them later.
- **Do level multipliers stack?** No. Only the highest threshold you have reached applies, and it replaces the lower ones entirely rather than compounding with them.
- **Does a multiplier change how fast my banked XP drains?** No. In-game XP always consumes your balance one for one. Multipliers only affect what goes in.
- **I banked XP and got a different number than I typed.** A level multiplier is in force for that skill. The **Bank XP** form shows the result before you press the button, and the **LEVEL MULTIPLIERS** tab shows what each skill is banking at.
- **Where do I change the red screen flash?** RuneLite Configuration (the wrench) → **IRL XP** → *Almost-out warning (red screen flash)* → **How you are warned**.

## Troubleshooting
- **Plugin doesn’t appear:** Confirm `~/.runelite/credentials.properties` exists, close the official client, and start the development client with `./gradlew run`.
- **Timers not moving:** Verify you selected an action and clicked Start. Timers only tick while RuneLite stays open.
- **Overlay missing:** It hides when there is no banked XP, no XP owed, and no active timers. Add XP or start a timer to show it.
- **Overlay is the wrong size after changing screen mode:** Hold ALT (RuneLite's overlay drag key) and drag its edge to resize, or ALT + right-click it to reset to the default.
- **Depletion warning never fires:** It needs a few XP drops in that skill first to learn what one action costs, and it only fires for skills that have banked XP. Check that **Warn N actions early** is not `0`, and that **Depletion warning** is enabled.
- **Build issues:** Run `./gradlew --version` and confirm Gradle sees JDK 11 or newer. Plugin source is compiled for Java 11 to match Plugin Hub requirements.

## Support
- Open an issue in this repository if something breaks or a feature is unclear.
- Community help is also available in the RuneLite Discord (#plugin-support) for external plugins.

## Changelog (high level)
- **Unreleased:** Fixed every client restart charging a skill’s entire XP total against its bank, and the almost-out warning firing on login: the XP baseline was read before the login had delivered your stats, so it recorded 0 for every skill. It is now read once the login has finished, as RuneLite’s XP Tracker does. Banked XP can now go into debt: gaining more XP in-game than a skill has banked takes the balance negative, and the next XP banked for that skill pays the debt off before counting as banked. Banked XP is now also saved to a plugin-owned file and restored from it when RuneLite’s config copy is older or missing, fixing balances vanishing after a restart; the low-XP and almost-out warnings genuinely fire once instead of re-arming on every XP drop. Per-skill level multipliers with as many thresholds as you want; settings panel regrouped into named, self-describing sections and the sidebar now signposts where every option lives; remove banked XP to fix mistakes; overlay honours drag-resizing and scales its font; clearer labels and preview in “Log completed work”; screen flashes red when a skill is a few actions from empty.
- **0.0.1:** Initial release with manual XP banking, custom actions, timers, overlay updates, and persistence.

## Extras
- Sample actions: see `docs/example-actions.json` for ready-made activity ideas.
- Screenshots: add UI and overlay screenshots under `docs/screenshots/` (placeholders, not included in this repo).
