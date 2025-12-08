# GOK IRL Banked XP

Turn your real-life effort into RuneScape progress. Bank XP for any skill by logging activities, creating custom IRL actions, and running timers that add XP while you work out, study, or move around.

## What Does This Plugin Do?
- Bank XP for any skill and let the plugin subtract it automatically as you train in-game.
- Create your own IRL actions (walking, push-ups, studying) with XP rewards for one or more skills.
- Start timers so XP builds up while you perform the activity in real life.
- See totals in the sidebar and on an overlay so you always know what’s left.

## Features
- **Manual XP banking:** Add XP chunks to any skill from the “IRL XP” tab.
- **Custom actions:** Define IRL activities with default XP rates and per-skill overrides.
- **Real-time timers:** Run multiple timers at once; pause, resume, or stop them anytime.
- **Overlay summaries:** View banked XP totals and active timers on-screen.
- **Auto-save:** Actions, timers, and banked XP persist between RuneLite sessions.

## Installation (Manual)
The plugin is not yet on the Plugin Hub. To install locally:
1. Build the JAR (or download a release JAR if provided).
   ```bash
   ./gradlew clean build
   ```
   The file `build/libs/GOK-IRL-Banked_XP-0.0.1.jar` is produced.
2. Enable developer mode in RuneLite:
   - Run RuneLite with `--developer-mode` or toggle Developer Mode in settings.
3. Allow external plugins and create the plugins folder if needed:
   - Windows: `%userprofile%\.runelite\plugins`
   - macOS/Linux: `~/.runelite/plugins`
4. Copy the JAR into that plugins folder.
5. Restart RuneLite and enable **GOK IRL Banked XP** in the Plugins panel.

## Quick Start (5 Minutes)
1. **Install and enable** the plugin (see above).
2. Open the **IRL Actions** tab (new sidebar button).
3. Click **New Action** and create “Walking” with `50 XP/min` to Agility (add Hitpoints 25 XP/min if you like).
4. Select the action in the Timers section and click **Start Timer**. Watch the overlay for elapsed time and rates.
5. Train that skill in-game; stored XP will drop as you gain real XP.
6. Use the **IRL XP** tab any time to bank a one-off XP chunk without making an action.

## Using the Plugin
### Panels
- **IRL XP tab:** Quick manual XP banking and a summary of remaining banked XP.
- **IRL Actions tab:** Create/edit/delete actions, start/stop/pause timers, and view active timers.

### Creating Actions
1. Click **New Action**.
2. Enter a name (e.g., “Push-ups”), pick a time unit (Seconds/Minutes/Hours), and set a default XP per unit.
3. Tick the skills you want to reward. Leave “Use default” checked to apply the default rate, or enter a custom XP amount per skill.
4. Save. Actions persist across restarts.

### Timers
- Select an action in the Timers section and click **Start Timer**.
- Use **Pause**, **Resume**, or **Stop** on the selected timer.
- Multiple timers can run at once; each action keeps its own XP rates and elapsed time.
- Timers only run while RuneLite is open. They pause on shutdown and continue when you reopen the client.

### Manual XP Banking
- Open the **IRL XP** tab, choose a skill, enter an XP chunk, and click **Add**.
- The overlay and panel will show the updated totals. XP is consumed automatically as you gain it in-game for that skill.

### Overlay
- Shows total banked XP plus per-skill amounts (highlighted when under your low threshold).
- Lists active timers with elapsed time and XP rates so you can see what’s running without opening the sidebar.

### Data Persistence
- Actions, timers, and banked XP save automatically via RuneLite’s config. No export is required to keep your progress.

## Configuration
- **Low XP threshold:** Highlight skills in the overlay when banked XP falls below this number (default 500).
- **Chat warning:** Send a one-time chat message per skill the first time it drops below the threshold.
- All action/timer data is stored automatically; there are no extra config switches for them.

## FAQ
- **Does XP accumulate when RuneLite is closed?** No. Timers pause when the client is closed and resume when you reopen.
- **Can I run multiple timers?** Yes. Start as many as you need for different actions.
- **How do I reset everything?** Disable the plugin and clear its settings in RuneLite’s configuration (search for “GOK IRL Banked XP”).
- **Where do I see totals?** The overlay shows totals and active timers; the IRL XP tab lists banked XP by skill.
- **Do I need to re-create actions each time?** No, actions persist. You can also edit or delete them later.

## Troubleshooting
- **Plugin doesn’t appear:** Ensure developer mode and external plugins are enabled, and the JAR is in the correct plugins folder.
- **Timers not moving:** Verify you selected an action and clicked Start. Timers only tick while RuneLite stays open.
- **Overlay missing:** It hides when there is no banked XP and no active timers. Add XP or start a timer to show it.
- **Build issues:** Use Java 21 for builds (`JAVA_HOME` should point to a JDK 21 install). Gradle may complain on newer JDKs.

## Support
- Open an issue in this repository if something breaks or a feature is unclear.
- Community help is also available in the RuneLite Discord (#plugin-support) for external plugins.

## Changelog (high level)
- **0.0.1:** Initial release with manual XP banking, custom actions, timers, overlay updates, and persistence.

## Extras
- Sample actions: see `docs/example-actions.json` for ready-made activity ideas.
- Screenshots: add UI and overlay screenshots under `docs/screenshots/` (placeholders, not included in this repo).
